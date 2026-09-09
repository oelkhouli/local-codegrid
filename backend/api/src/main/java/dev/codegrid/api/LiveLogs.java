package dev.codegrid.api;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.server.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.*;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.web.util.UriComponentsBuilder;

@Configuration
@EnableWebSocket
public class LiveLogs extends TextWebSocketHandler implements WebSocketConfigurer {
  private final AuthService auth;
  private final JobService jobs;
  private final Events events;
  private final Json json;
  private final String origin;
  private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
  private final ThreadPoolExecutor senders =
      new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64));

  private static class Connection {
    final WebSocketSession session;
    final AuthService.User user;
    final UUID job;
    final AtomicBoolean busy = new AtomicBoolean();
    long cursor;

    Connection(WebSocketSession s, AuthService.User u, UUID j, long c) {
      session = new ConcurrentWebSocketSessionDecorator(s, 2000, 65536);
      user = u;
      job = j;
      cursor = c;
    }
  }

  public LiveLogs(
      AuthService auth,
      JobService jobs,
      Events events,
      Json json,
      @Value("${codegrid.origin}") String origin) {
    this.auth = auth;
    this.jobs = jobs;
    this.events = events;
    this.json = json;
    this.origin = origin;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(this, "/ws/jobs/*")
        .setAllowedOrigins(origin)
        .addInterceptors(
            new HandshakeInterceptor() {
              public boolean beforeHandshake(
                  ServerHttpRequest req,
                  ServerHttpResponse res,
                  WebSocketHandler h,
                  Map<String, Object> attrs) {
                try {
                  if (!origin.equals(req.getHeaders().getOrigin())) return false;
                  var user =
                      (AuthService.User) ((Authentication) req.getPrincipal()).getPrincipal();
                  String path = req.getURI().getPath();
                  UUID job = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
                  jobs.owner(user, job);
                  String after =
                      UriComponentsBuilder.fromUri(req.getURI())
                          .build()
                          .getQueryParams()
                          .getFirst("after");
                  attrs.put("user", user);
                  attrs.put("job", job);
                  attrs.put("after", after == null ? 0L : Math.max(0, Long.parseLong(after)));
                  return true;
                } catch (Exception e) {
                  return false;
                }
              }

              public void afterHandshake(
                  ServerHttpRequest r, ServerHttpResponse s, WebSocketHandler h, Exception e) {}
            });
  }

  @Bean
  ServletServerContainerFactoryBean webSocketContainer() {
    var c = new ServletServerContainerFactoryBean();
    c.setMaxTextMessageBufferSize(512);
    c.setMaxBinaryMessageBufferSize(512);
    c.setAsyncSendTimeout(2000L);
    c.setMaxSessionIdleTimeout(60000L);
    return c;
  }

  @Override
  public synchronized void afterConnectionEstablished(WebSocketSession session) throws Exception {
    var a = session.getAttributes();
    var user = (AuthService.User) a.get("user");
    if (connections.size() >= 64
        || connections.values().stream().filter(c -> c.user.id().equals(user.id())).count() >= 3) {
      session.close(CloseStatus.POLICY_VIOLATION);
      return;
    }
    connections.put(
        session.getId(), new Connection(session, user, (UUID) a.get("job"), (Long) a.get("after")));
  }

  @Override
  protected void handleTextMessage(WebSocketSession s, TextMessage m) throws Exception {
    s.close(CloseStatus.POLICY_VIOLATION);
  }

  @Override
  public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
    connections.remove(s.getId());
  }

  @Scheduled(fixedDelay = 500)
  public void flush() {
    for (var c : connections.values())
      if (c.busy.compareAndSet(false, true))
        try {
          senders.execute(() -> send(c));
        } catch (RejectedExecutionException e) {
          c.busy.set(false);
        }
  }

  private void send(Connection c) {
    try {
      if (auth.sessionHash(c.user.sessionHash()) == null) {
        c.session.close(CloseStatus.POLICY_VIOLATION);
        return;
      }
      for (var e : events.after(c.job, c.cursor)) {
        c.session.sendMessage(new TextMessage(json.write(e)));
        c.cursor = ((Number) e.get("seq")).longValue();
      }
    } catch (Exception e) {
      try {
        c.session.close(CloseStatus.SERVER_ERROR);
      } catch (Exception ignored) {
      }
    } finally {
      c.busy.set(false);
    }
  }

  @PreDestroy
  public void close() {
    senders.shutdownNow();
  }
}
