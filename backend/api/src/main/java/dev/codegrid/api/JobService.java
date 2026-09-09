package dev.codegrid.api;

import dev.codegrid.domain.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobService {
  public record CaseSpec(String input, String expected) {}

  public record Submission(Language language, String source, List<CaseSpec> tests, String mode) {}

  private final JdbcTemplate db;
  private final Json json;
  private final Events events;
  private final AuthService auth;
  private final RateLimit rate;
  private final StringRedisTemplate redis;
  private final MeterRegistry metrics;

  public JobService(
      JdbcTemplate db,
      Json json,
      Events events,
      AuthService auth,
      RateLimit rate,
      StringRedisTemplate redis,
      MeterRegistry metrics) {
    this.db = db;
    this.json = json;
    this.events = events;
    this.auth = auth;
    this.rate = rate;
    this.redis = redis;
    this.metrics = metrics;
  }

  public static Instant instant(Object value) {
    return ((Timestamp) value).toInstant();
  }

  public Instant now() {
    return db.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
  }

  static void size(String value, int max) {
    if (value == null || value.getBytes(StandardCharsets.UTF_8).length > max)
      throw new ApiException(400, "Text exceeds the allowed byte limit");
  }

  @Transactional
  public Map<String, Object> submit(AuthService.User user, String key, Submission request) {
    if (key == null || !key.matches("[A-Za-z0-9_-]{8,100}"))
      throw new ApiException(400, "An 8–100 character Idempotency-Key is required");
    if (request == null
        || request.language() == null
        || request.mode() == null
        || !Set.of("RUN", "BENCHMARK").contains(request.mode()))
      throw new ApiException(400, "Invalid language or mode");
    size(request.source(), 32768);
    if (request.tests() == null || request.tests().isEmpty() || request.tests().size() > 3)
      throw new ApiException(400, "Provide one to three test cases");
    for (var t : request.tests()) {
      if (t == null) throw new ApiException(400, "Invalid case");
      size(t.input(), 4096);
      size(t.expected(), 8192);
    }
    String hash = AuthService.hash(json.write(request));
    // A per-user row lock serializes idempotency and active-job quota decisions across API
    // replicas.
    db.queryForMap("SELECT id FROM users WHERE id=? FOR UPDATE", user.id());
    var old =
        db.queryForList("SELECT * FROM idempotency WHERE owner_id=? AND key=?", user.id(), key);
    if (!old.isEmpty()) {
      if (!old.get(0).get("request_hash").equals(hash))
        throw new ApiException(409, "Idempotency key reused with different content");
      return Map.of("id", old.get(0).get("job_id"), "replayed", true);
    }
    rate.take("submit:" + user.id(), 12, 2.0);
    db.queryForList("SELECT pg_advisory_xact_lock(74002)");
    long active =
        db.queryForObject(
            "SELECT count(*) FROM jobs WHERE owner_id=? AND state NOT IN ('FINISHED','CANCELLED')",
            Long.class,
            user.id());
    if (active >= 4) throw new ApiException(429, "At most four active jobs are allowed per user");
    if (db.queryForObject("SELECT count(*) FROM jobs", Long.class) >= 10000)
      throw new ApiException(
          507, "Local history capacity reached; export and reset through the operator tools");
    UUID submission = UUID.randomUUID(), job = UUID.randomUUID();
    db.update(
        "INSERT INTO submissions(id,owner_id,language,source,tests,mode)"
            + " VALUES(?,?,?,?,?::jsonb,?)",
        submission,
        user.id(),
        request.language().name(),
        request.source(),
        json.write(request.tests()),
        request.mode());
    db.update(
        "INSERT INTO jobs(id,submission_id,owner_id,state,deadline)"
            + " VALUES(?,?,?,'QUEUED',clock_timestamp()+interval '5 minutes')",
        job,
        submission,
        user.id());
    db.update(
        "INSERT INTO idempotency(owner_id,key,request_hash,job_id) VALUES(?,?,?,?)",
        user.id(),
        key,
        hash,
        job);
    events.emit(job, "STATUS", Map.of("state", "QUEUED"));
    auth.audit(user.id().toString(), "SUBMIT", job.toString());
    metrics.counter("codegrid.jobs.accepted", "language", request.language().name()).increment();
    return Map.of("id", job, "replayed", false);
  }

  public void owner(AuthService.User user, UUID job) {
    if (db.queryForObject(
            "SELECT count(*) FROM jobs WHERE id=? AND owner_id=?", Long.class, job, user.id())
        == 0) {
      auth.audit(user.id().toString(), "ACCESS_DENIED", job.toString());
      throw new ApiException(404, "Job not found");
    }
  }

  public List<Map<String, Object>> list(AuthService.User user) {
    return db.queryForList(
        "SELECT j.*,s.language,s.mode FROM jobs j JOIN submissions s ON s.id=j.submission_id WHERE"
            + " j.owner_id=? ORDER BY j.created_at DESC LIMIT 100",
        user.id());
  }

  public Map<String, Object> get(AuthService.User user, UUID id) {
    owner(user, id);
    var job = db.queryForMap("SELECT * FROM jobs WHERE id=?", id);
    job.put("submission", submission((UUID) job.get("submission_id")));
    var attempts =
        db.queryForList(
            "SELECT"
                + " generation,worker_id,node_id,state,lease_until,deadline,started_at,ended_at,cleaned,expected_ms,image_id,result"
                + " FROM attempts WHERE job_id=? ORDER BY generation",
            id);
    attempts.forEach(
        a -> {
          if (a.get("result") != null) a.put("result", json.read(a.get("result").toString()));
        });
    job.put("attempts", attempts);
    return job;
  }

  public Object submission(UUID id) {
    String key = "submission:" + id;
    try {
      String value = redis.opsForValue().get(key);
      if (value != null) return json.read(value);
    } catch (Exception ignored) {
    }
    var row = db.queryForMap("SELECT language,source,tests,mode FROM submissions WHERE id=?", id);
    row.put("tests", json.read(row.get("tests").toString()));
    try {
      redis.opsForValue().set(key, json.write(row), Duration.ofMinutes(5));
    } catch (Exception ignored) {
    }
    return row;
  }

  @Transactional
  public Map<String, Object> cancel(AuthService.User user, UUID id) {
    owner(user, id);
    var row = db.queryForMap("SELECT * FROM jobs WHERE id=? FOR UPDATE", id);
    JobState current = JobState.valueOf((String) row.get("state"));
    if (!current.isTerminal()) {
      new JobLifecycle().transition(current, JobEvent.CANCEL);
      db.update(
          "UPDATE jobs SET state='CANCELLED',verdict='CANCELLED',finished_at=clock_timestamp()"
              + " WHERE id=?",
          id);
      db.update(
          "UPDATE attempts SET state='CANCELLED',ended_at=clock_timestamp() WHERE job_id=? AND"
              + " state IN ('LEASED','RUNNING')",
          id);
      events.emit(id, "STATUS", Map.of("state", "CANCELLED", "verdict", "CANCELLED"));
      auth.audit(user.id().toString(), "CANCEL", id.toString());
    }
    return Map.of("id", id, "state", current.isTerminal() ? current.name() : "CANCELLED");
  }

  public Map<String, Object> overview(AuthService.User user) {
    return db.queryForMap(
        """
SELECT count(*) AS total, count(*) FILTER(WHERE state NOT IN ('FINISHED','CANCELLED')) AS active,
count(*) FILTER(WHERE verdict='ACCEPTED') AS accepted,
percentile_cont(0.5) WITHIN GROUP(ORDER BY extract(epoch FROM finished_at-created_at)*1000) AS p50_ms,
percentile_cont(0.95) WITHIN GROUP(ORDER BY extract(epoch FROM finished_at-created_at)*1000) AS p95_ms,
percentile_cont(0.99) WITHIN GROUP(ORDER BY extract(epoch FROM finished_at-created_at)*1000) AS p99_ms
FROM jobs WHERE owner_id=? AND created_at>clock_timestamp()-interval '24 hours'
""",
        user.id());
  }
}
