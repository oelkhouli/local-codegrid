package dev.codegrid.api;

import jakarta.servlet.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class ApiController {
  private final AuthService auth;
  private final JobService jobs;
  private final Events events;
  private final WorkerService workers;
  private final JdbcTemplate db;
  private final boolean secure;
  private final ProblemService problems;

  public ApiController(
      AuthService auth,
      JobService jobs,
      Events events,
      WorkerService workers,
      ProblemService problems,
      JdbcTemplate db,
      @Value("${codegrid.secure-cookie}") boolean secure) {
    this.auth = auth;
    this.jobs = jobs;
    this.events = events;
    this.workers = workers;
    this.problems = problems;
    this.db = db;
    this.secure = secure;
  }

  public record Credentials(String username, String password) {}

  private AuthService.User user(Authentication a) {
    return (AuthService.User) a.getPrincipal();
  }

  private String node(HttpServletRequest r) {
    return (String) r.getAttribute("node");
  }

  @GetMapping("/api/health")
  Map<String, String> health() {
    db.queryForObject("SELECT 1", Integer.class);
    return Map.of("status", "ok");
  }

  @PostMapping("/api/auth/register")
  ResponseEntity<?> register(@RequestBody Credentials c, HttpServletRequest r) {
    auth.register(c.username(), c.password(), r.getRemoteAddr());
    return ResponseEntity.status(201).body(Map.of("created", true));
  }

  @PostMapping("/api/auth/login")
  ResponseEntity<?> login(@RequestBody Credentials c, HttpServletRequest r) {
    var result = auth.login(c.username(), c.password(), r.getRemoteAddr());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie(result.token(), Duration.ofHours(8)))
        .body(result.user().view());
  }

  private String cookie(String value, Duration age) {
    return ResponseCookie.from("codegrid_session", value)
        .httpOnly(true)
        .secure(secure)
        .sameSite("Strict")
        .path("/")
        .maxAge(age)
        .build()
        .toString();
  }

  @GetMapping("/api/session")
  Object session(Authentication a) {
    return user(a).view();
  }

  @PostMapping("/api/logout")
  ResponseEntity<?> logout(Authentication a) {
    auth.logout(user(a));
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO))
        .body(Map.of("logged_out", true));
  }

  @PostMapping("/api/jobs")
  ResponseEntity<?> submit(
      Authentication a,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody JobService.Submission request) {
    return ResponseEntity.accepted().body(jobs.submit(user(a), key, request));
  }

  @GetMapping("/api/problems")
  Object problems() {
    return problems.list();
  }

  @GetMapping("/api/jobs")
  Object list(Authentication a) {
    return jobs.list(user(a));
  }

  @GetMapping("/api/jobs/{id}")
  Object job(Authentication a, @PathVariable UUID id) {
    return jobs.get(user(a), id);
  }

  @GetMapping("/api/jobs/{id}/events")
  Object events(
      Authentication a, @PathVariable UUID id, @RequestParam(defaultValue = "0") long after) {
    jobs.owner(user(a), id);
    return events.after(id, Math.max(0, after));
  }

  @PostMapping("/api/jobs/{id}/cancel")
  Object cancel(Authentication a, @PathVariable UUID id) {
    return jobs.cancel(user(a), id);
  }

  @GetMapping("/api/overview")
  Object overview(Authentication a) {
    return jobs.overview(user(a));
  }

  @GetMapping("/api/nodes")
  Object nodes() {
    return db.queryForList(
        """
SELECT n.id,n.cpu_budget,n.memory_budget,n.slots,n.speed,n.load,n.heartbeat,n.quarantined,
  (SELECT count(*) FROM attempts a WHERE a.node_id=n.id AND NOT a.cleaned) AS reserved_slots,
  (SELECT count(*) FROM workers w WHERE w.node_id=n.id AND w.heartbeat>clock_timestamp()-interval '10 seconds') AS workers
FROM nodes n ORDER BY n.id
""");
  }

  @GetMapping("/api/admin/audit")
  Object audit(Authentication a) {
    auth.admin(user(a));
    return db.queryForList("SELECT * FROM audit_log ORDER BY id DESC LIMIT 100");
  }

  public record NodeSpec(String id, int cpu, long memory, int slots, double speed) {}

  @PostMapping("/api/admin/nodes")
  Object addNode(Authentication a, @RequestBody NodeSpec spec) {
    auth.admin(user(a));
    if (spec.id() == null
        || !spec.id().matches("[A-Za-z0-9_-]{1,64}")
        || spec.cpu() < 1000
        || spec.cpu() > 32000
        || spec.memory() < 536870912
        || spec.slots() < 1
        || spec.slots() > 32
        || !Double.isFinite(spec.speed())
        || spec.speed() <= 0
        || spec.speed() > 100) throw new ApiException(400, "Invalid node budget");
    String token = AuthService.token();
    int n =
        db.update(
            "INSERT INTO nodes(id,token_hash,cpu_budget,memory_budget,slots,speed)"
                + " VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING",
            spec.id(),
            AuthService.hash(token),
            spec.cpu(),
            spec.memory(),
            spec.slots(),
            spec.speed());
    if (n == 0) throw new ApiException(409, "Node already exists");
    auth.audit(user(a).id().toString(), "NODE_CREATE", spec.id());
    return Map.of("id", spec.id(), "token", token);
  }

  @PostMapping("/internal/bind-ledger")
  Object bindLedger(HttpServletRequest r, @RequestBody Map<String, String> body) {
    UUID id = UUID.fromString(body.get("ledger"));
    if (db.update(
            "UPDATE nodes SET ledger_id=? WHERE id=? AND (ledger_id IS NULL OR ledger_id=?)",
            id,
            node(r),
            id)
        == 0) throw new ApiException(409, "This node ID belongs to a different shared ledger");
    return Map.of("bound", true);
  }

  @GetMapping("/internal/config")
  Object config(HttpServletRequest r) {
    return db.queryForMap("SELECT cpu_budget,memory_budget,slots FROM nodes WHERE id=?", node(r));
  }

  @PostMapping("/internal/register")
  Object register(HttpServletRequest r, @RequestBody WorkerService.Registration request) {
    workers.register(node(r), request);
    return Map.of("registered", true);
  }

  @GetMapping("/internal/assignment")
  Object assignment(HttpServletRequest r, @RequestParam UUID worker) {
    return workers.assignment(node(r), worker);
  }

  @PostMapping("/internal/heartbeat")
  Object heartbeat(HttpServletRequest r, @RequestParam UUID worker) {
    workers.heartbeat(node(r), worker);
    return Map.of("ok", true);
  }

  @PostMapping("/internal/node-heartbeat")
  Object nodeHeartbeat(HttpServletRequest r, @RequestBody Map<String, Double> body) {
    return workers.nodeHeartbeat(node(r), body.getOrDefault("load", 0.0));
  }

  @GetMapping("/internal/cleanup")
  Object cleanup(HttpServletRequest r) {
    return workers.cleanupCandidates(node(r));
  }

  @PostMapping("/internal/jobs/{job}/{generation}/cleaned")
  Object cleaned(HttpServletRequest r, @PathVariable UUID job, @PathVariable int generation) {
    workers.cleaned(node(r), job, generation);
    return Map.of("cleaned", true);
  }

  @PostMapping("/internal/jobs/{job}/{generation}/renew")
  Object renew(
      HttpServletRequest r,
      @PathVariable UUID job,
      @PathVariable int generation,
      @RequestParam UUID worker,
      @RequestParam(defaultValue = "false") boolean start) {
    return workers.renew(node(r), worker, job, generation, start);
  }

  @PostMapping("/internal/jobs/{job}/{generation}/logs")
  Object logs(
      HttpServletRequest r,
      @PathVariable UUID job,
      @PathVariable int generation,
      @RequestParam UUID worker,
      @RequestBody WorkerService.LogChunk chunk) {
    workers.log(node(r), worker, job, generation, chunk);
    return Map.of("ack", chunk.sequence());
  }

  @PostMapping("/internal/jobs/{job}/{generation}/complete")
  Object complete(
      HttpServletRequest r,
      @PathVariable UUID job,
      @PathVariable int generation,
      @RequestParam UUID worker,
      @RequestBody Map<String, Object> result) {
    workers.complete(node(r), worker, job, generation, result);
    return Map.of("accepted", true);
  }
}
