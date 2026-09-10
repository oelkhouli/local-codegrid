package dev.codegrid.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class ApiSecurity {
  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      AuthService auth,
      RateLimit rate,
      @Value("${codegrid.origin}") String origin)
      throws Exception {
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/auth/**",
                        "/api/health",
                        "/internal/**",
                        "/actuator/health/**",
                        "/actuator/prometheus")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e -> e.authenticationEntryPoint((r, s, x) -> error(s, 401, "Login required")))
        .addFilterBefore(new Gate(auth, rate, origin), UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  static void error(HttpServletResponse r, int status, String message) throws IOException {
    r.setStatus(status);
    r.setContentType("application/json");
    r.getWriter().write("{\"error\":\"" + message + "\"}");
  }

  private static class Gate extends OncePerRequestFilter {
    final AuthService auth;
    final RateLimit rate;
    final String origin;

    Gate(AuthService a, RateLimit r, String o) {
      auth = a;
      rate = r;
      origin = o;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws IOException, ServletException {
      res.setHeader("X-Content-Type-Options", "nosniff");
      res.setHeader("Cache-Control", "no-store");
      try {
        boolean unsafe = !Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod());
        String requestOrigin = req.getHeader("Origin");
        if (requestOrigin != null && !requestOrigin.equals(origin))
          throw new ApiException(403, "Origin rejected");
        boolean internal = req.getRequestURI().startsWith("/internal/");
        if (internal) {
          String node = auth.node(req.getHeader("X-CodeGrid-Node"), req.getHeader("Authorization"));
          req.setAttribute("node", node);
          if (!Set.of("/internal/config", "/internal/bind-ledger").contains(req.getRequestURI()))
            auth.ledger(node, req.getHeader("X-CodeGrid-Ledger"));
        } else {
          String token = null;
          if (req.getCookies() != null)
            for (Cookie c : req.getCookies())
              if (c.getName().equals("codegrid_session")) token = c.getValue();
          var user = auth.session(token);
          if (user != null) {
            if (unsafe
                && !req.getRequestURI().startsWith("/api/auth/")
                && !AuthService.equal(user.csrf(), req.getHeader("X-CSRF-Token")))
              throw new ApiException(403, "CSRF token required");
            rate.take("user:" + user.id(), 120, 20);
            SecurityContextHolder.getContext()
                .setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role()))));
          }
        }
        if (unsafe) {
          int maxBody = internal ? 131072 : 65536;
          if (req.getContentLengthLong() > maxBody)
            throw new ApiException(413, "Request body too large");
          byte[] body = req.getInputStream().readNBytes(maxBody + 1);
          if (body.length > maxBody) throw new ApiException(413, "Request body too large");
          req = new Body(req, body);
        }
        chain.doFilter(req, res);
      } catch (ApiException e) {
        error(res, e.status, e.getMessage());
      }
    }
  }

  private static class Body extends HttpServletRequestWrapper {
    private final byte[] bytes;

    Body(HttpServletRequest r, byte[] b) {
      super(r);
      bytes = b;
    }

    @Override
    public ServletInputStream getInputStream() {
      var input = new ByteArrayInputStream(bytes);
      return new ServletInputStream() {
        public int read() {
          return input.read();
        }

        public boolean isFinished() {
          return input.available() == 0;
        }

        public boolean isReady() {
          return true;
        }

        public void setReadListener(ReadListener r) {
          throw new UnsupportedOperationException();
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
  }
}
