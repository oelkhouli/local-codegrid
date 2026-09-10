package dev.codegrid.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService implements ApplicationRunner {
  public record User(UUID id, String username, String role, String csrf, String sessionHash) {
    public Map<String, Object> view() {
      return Map.of("id", id, "username", username, "role", role, "csrf", csrf);
    }
  }

  public record Login(User user, String token) {}

  private final JdbcTemplate db;
  private final RateLimit rate;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
  private final String dummy = passwords.encode("constant-time-unknown-account");
  private final String adminPassword, nodeToken, nodeId;
  private final int nodeCpu, nodeSlots;
  private final long nodeMemory;

  public AuthService(
      JdbcTemplate db,
      RateLimit rate,
      @Value("${codegrid.admin-password}") String password,
      @Value("${codegrid.node-token}") String token,
      @Value("${codegrid.node-id}") String nodeId,
      @Value("${codegrid.node-cpu}") int cpu,
      @Value("${codegrid.node-memory}") long memory,
      @Value("${codegrid.node-slots}") int slots) {
    this.db = db;
    this.rate = rate;
    adminPassword = password;
    nodeToken = token;
    this.nodeId = nodeId;
    nodeCpu = cpu;
    nodeMemory = memory;
    nodeSlots = slots;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    validatePassword(adminPassword);
    if (nodeToken.length() < 32)
      throw new IllegalStateException("NODE_TOKEN must contain at least 32 characters");
    db.update(
        "INSERT INTO users(id,username,password_hash,role) VALUES(?,?,?,'ADMIN') ON"
            + " CONFLICT(username) DO NOTHING",
        UUID.randomUUID(),
        "admin",
        passwords.encode(adminPassword));
    db.update(
        "INSERT INTO nodes(id,token_hash,cpu_budget,memory_budget,slots) VALUES(?,?,?,?,?) ON"
            + " CONFLICT(id) DO NOTHING",
        nodeId,
        hash(nodeToken),
        nodeCpu,
        nodeMemory,
        nodeSlots);
  }

  public static String token() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static boolean equal(String a, String b) {
    return a != null
        && b != null
        && MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  private static String username(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_]{3,32}"))
      throw new ApiException(400, "Username must contain 3–32 letters, digits or underscores");
    return value.toLowerCase(Locale.ROOT);
  }

  private static void validatePassword(String value) {
    if (value == null || value.length() < 12 || value.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new ApiException(
          400, "Password must be at least 12 characters and at most 72 UTF-8 bytes");
  }

  @Transactional
  public void register(String name, String password, String ip) {
    rate.take("register:" + hash(ip), 4, 1.0 / 60);
    name = username(name);
    validatePassword(password);
    db.queryForList("SELECT pg_advisory_xact_lock(74000)");
    if (db.queryForObject("SELECT count(*) FROM users", Long.class) >= 100)
      throw new ApiException(429, "Local account capacity reached");
    int n =
        db.update(
            "INSERT INTO users(id,username,password_hash,role) VALUES(?,?,?,'USER') ON"
                + " CONFLICT(username) DO NOTHING",
            UUID.randomUUID(),
            name,
            passwords.encode(password));
    if (n == 0) throw new ApiException(409, "Username unavailable");
    audit(name, "REGISTER", null);
  }

  public Login login(String name, String password, String ip) {
    rate.take("login:" + hash(ip), 10, 1.0 / 6);
    name = username(name);
    validatePassword(password);
    var rows = db.queryForList("SELECT * FROM users WHERE username=?", name);
    boolean valid =
        passwords.matches(
            password, rows.isEmpty() ? dummy : (String) rows.get(0).get("password_hash"));
    if (!valid || rows.isEmpty()) {
      audit(name, "LOGIN_FAILED", hash(ip));
      throw new ApiException(401, "Invalid username or password");
    }
    var row = rows.get(0);
    String token = token(), csrf = token();
    UUID id = (UUID) row.get("id");
    db.update(
        "INSERT INTO sessions(token_hash,user_id,csrf,expires_at)"
            + " VALUES(?,?,?,clock_timestamp()+interval '8 hours')",
        hash(token),
        id,
        csrf);
    audit(id.toString(), "LOGIN", null);
    return new Login(new User(id, name, (String) row.get("role"), csrf, hash(token)), token);
  }

  public User session(String rawToken) {
    if (rawToken == null || rawToken.length() > 100) return null;
    return sessionHash(hash(rawToken));
  }

  public User sessionHash(String hash) {
    var rows =
        db.queryForList(
            "SELECT u.*,s.csrf FROM sessions s JOIN users u ON u.id=s.user_id WHERE token_hash=?"
                + " AND expires_at>clock_timestamp()",
            hash);
    if (rows.isEmpty()) return null;
    var r = rows.get(0);
    return new User(
        (UUID) r.get("id"),
        (String) r.get("username"),
        (String) r.get("role"),
        (String) r.get("csrf"),
        hash);
  }

  public void logout(User user) {
    db.update("DELETE FROM sessions WHERE token_hash=?", user.sessionHash());
    audit(user.id().toString(), "LOGOUT", null);
  }

  public String node(String id, String authorization) {
    if (id == null
        || !id.matches("[A-Za-z0-9_-]{1,64}")
        || authorization == null
        || !authorization.startsWith("Bearer "))
      throw new ApiException(401, "Node credentials required");
    var rows = db.queryForList("SELECT token_hash FROM nodes WHERE id=?", id);
    if (rows.isEmpty()
        || !equal((String) rows.get(0).get("token_hash"), hash(authorization.substring(7))))
      throw new ApiException(401, "Invalid node credentials");
    return id;
  }

  public void ledger(String node, String supplied) {
    var values = db.queryForList("SELECT ledger_id FROM nodes WHERE id=?", node);
    if (supplied == null
        || values.isEmpty()
        || values.get(0).get("ledger_id") == null
        || !values.get(0).get("ledger_id").toString().equals(supplied))
      throw new ApiException(409, "Node ledger identity mismatch");
  }

  public void admin(User user) {
    if (!"ADMIN".equals(user.role())) throw new ApiException(403, "Administrator access required");
  }

  public void audit(String actor, String action, String target) {
    db.update("INSERT INTO audit_log(actor,action,target) VALUES(?,?,?)", actor, action, target);
  }
}
