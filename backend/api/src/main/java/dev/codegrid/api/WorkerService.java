package dev.codegrid.api;

import dev.codegrid.domain.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.random.RandomGenerator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkerService {
  private final JdbcTemplate db;
  private final Json json;
  private final Events events;
  private final JobService jobs;
  private final MeterRegistry metrics;
  private final StringRedisTemplate redis;

  public WorkerService(
      JdbcTemplate db,
      Json json,
      Events events,
      JobService jobs,
      MeterRegistry metrics,
      StringRedisTemplate redis) {
    this.db = db;
    this.json = json;
    this.events = events;
    this.jobs = jobs;
    this.metrics = metrics;
    this.redis = redis;
  }

  public record Registration(UUID worker, Map<String, String> images) {}

  public record LogChunk(int sequence, String stream, String text) {}

  @Transactional
  public void register(String node, Registration registration) {
    if (registration.worker() == null
        || registration.images() == null
        || registration.images().isEmpty()
        || registration.images().size() > 4)
      throw new ApiException(400, "Worker and images required");
    for (var image : registration.images().entrySet()) {
      Language.valueOf(image.getKey());
      if (!image.getValue().matches("sha256:[a-f0-9]{64}"))
        throw new ApiException(400, "Images must be resolved to immutable IDs");
    }
    db.update(
        "INSERT INTO workers(id,node_id,languages,images,heartbeat)"
            + " VALUES(?,?,?::jsonb,?::jsonb,clock_timestamp()) ON CONFLICT(id) DO UPDATE SET"
            + " heartbeat=clock_timestamp() WHERE workers.node_id=EXCLUDED.node_id",
        registration.worker(),
        node,
        json.write(registration.images().keySet()),
        json.write(registration.images()));
  }

  public void heartbeat(String node, UUID worker) {
    if (db.update(
            "UPDATE workers SET heartbeat=clock_timestamp() WHERE id=? AND node_id=?", worker, node)
        == 0) throw new ApiException(404, "Register this worker first");
  }

  @Transactional
  public Map<String, Object> nodeHeartbeat(String node, double load) {
    if (!Double.isFinite(load) || load < 0 || load > 10000)
      throw new ApiException(400, "Invalid load");
    db.queryForMap("SELECT id FROM nodes WHERE id=? FOR UPDATE", node);
    db.update(
        """
UPDATE nodes SET heartbeat=clock_timestamp(),load=?,quarantined=EXISTS(
  SELECT 1 FROM attempts WHERE node_id=? AND NOT cleaned AND
    (state NOT IN ('LEASED','RUNNING') OR lease_until<=clock_timestamp() OR deadline<=clock_timestamp())) WHERE id=?
""",
        load,
        node,
        node);
    return db.queryForMap(
        "SELECT id,cpu_budget,memory_budget,slots,quarantined FROM nodes WHERE id=?", node);
  }

  public List<Map<String, Object>> cleanupCandidates(String node) {
    return db.queryForList(
        """
SELECT a.job_id,a.generation FROM attempts a JOIN jobs j ON j.id=a.job_id
WHERE a.node_id=? AND NOT a.cleaned AND (a.state NOT IN ('LEASED','RUNNING') OR a.lease_until<=clock_timestamp()
  OR a.deadline<=clock_timestamp() OR j.deadline<=clock_timestamp() OR j.state IN ('FINISHED','CANCELLED')) LIMIT 64
""",
        node);
  }

  @Transactional
  public void cleaned(String node, UUID job, int generation) {
    db.queryForMap("SELECT id FROM jobs WHERE id=? FOR UPDATE", job);
    var rows =
        db.queryForList(
            "SELECT * FROM attempts WHERE job_id=? AND generation=? AND node_id=?",
            job,
            generation,
            node);
    if (rows.isEmpty()) throw new ApiException(404, "Attempt not found");
    var a = rows.get(0);
    Instant now = jobs.now();
    if (Set.of("LEASED", "RUNNING").contains(a.get("state"))
        && now.isBefore(JobService.instant(a.get("lease_until")))
        && now.isBefore(JobService.instant(a.get("deadline"))))
      throw new ApiException(409, "Active attempt cannot release its reservation");
    // Physical cleanup is acknowledged only after the node lock/tombstone and runtime removal.
    db.queryForMap("SELECT id FROM nodes WHERE id=? FOR UPDATE", node);
    if (db.update(
            "UPDATE attempts SET cleaned=true WHERE job_id=? AND generation=? AND NOT cleaned",
            job,
            generation)
        > 0) events.emit(job, "CLEANUP", Map.of("generation", generation, "cleaned", true));
  }

  public Map<String, Object> assignment(String node, UUID worker) {
    heartbeat(node, worker);
    try {
      redis.opsForList().leftPop("assign:" + worker);
    } catch (Exception ignored) {
    }
    var rows =
        db.queryForList(
            """
SELECT j.id,j.generation,a.deadline,a.image_id,s.language,s.source,s.tests,s.mode
FROM jobs j JOIN attempts a ON a.job_id=j.id AND a.generation=j.generation
JOIN submissions s ON s.id=j.submission_id WHERE a.worker_id=? AND a.node_id=? AND j.state='LEASED'
  AND a.lease_until>clock_timestamp() AND a.deadline>clock_timestamp() AND j.deadline>clock_timestamp() LIMIT 1
""",
            worker,
            node);
    if (rows.isEmpty()) return Map.of();
    var result = rows.get(0);
    result.put("tests", json.read(result.get("tests").toString()));
    return result;
  }

  private Map<String, Object> locked(String node, UUID worker, UUID job, int generation) {
    var rows =
        db.queryForList(
            """
SELECT j.*,a.worker_id AS attempt_worker,a.node_id,a.lease_until,a.deadline AS attempt_deadline,
  a.last_chunk,a.result,a.report_hash,a.started_at AS attempt_started
FROM jobs j JOIN attempts a ON a.job_id=j.id AND a.generation=? WHERE j.id=? FOR UPDATE OF j
""",
            generation,
            job);
    if (rows.isEmpty()) throw new ApiException(404, "Attempt not found");
    var r = rows.get(0);
    if (!node.equals(r.get("node_id")) || !worker.equals(r.get("attempt_worker")))
      throw new ApiException(403, "Attempt ownership mismatch");
    return r;
  }

  private void guard(Map<String, Object> row, UUID worker, int generation) {
    if (!Fence.accepts(
        JobState.valueOf(row.get("state").toString()),
        ((Number) row.get("generation")).intValue(),
        generation,
        row.get("worker_id").toString(),
        worker.toString(),
        jobs.now(),
        JobService.instant(row.get("lease_until")),
        JobService.instant(row.get("attempt_deadline")),
        JobService.instant(row.get("deadline")))) {
      metrics.counter("codegrid.protocol.stale").increment();
      throw new ApiException(409, "Attempt authority expired or changed");
    }
  }

  @Transactional
  public Map<String, Object> renew(
      String node, UUID worker, UUID job, int generation, boolean start) {
    var row = locked(node, worker, job, generation);
    guard(row, worker, generation);
    if (start && row.get("state").equals("LEASED")) {
      new JobLifecycle().transition(JobState.LEASED, JobEvent.START);
      db.update("UPDATE jobs SET state='RUNNING' WHERE id=?", job);
      db.update(
          "UPDATE attempts SET state='RUNNING' WHERE job_id=? AND generation=?", job, generation);
      events.emit(job, "STATUS", Map.of("state", "RUNNING", "generation", generation));
    }
    Timestamp until =
        db.queryForObject(
            "UPDATE attempts SET lease_until=LEAST(clock_timestamp()+interval '15"
                + " seconds',deadline) WHERE job_id=? AND generation=? RETURNING lease_until",
            Timestamp.class,
            job,
            generation);
    return Map.of("lease_until", until.toInstant().toString());
  }

  @Transactional
  public void log(String node, UUID worker, UUID job, int generation, LogChunk chunk) {
    if (chunk.sequence() < 1 || !Set.of("stdout", "stderr", "system").contains(chunk.stream()))
      throw new ApiException(400, "Invalid log chunk");
    JobService.size(chunk.text(), 4096);
    var row = locked(node, worker, job, generation);
    guard(row, worker, generation);
    String hash = AuthService.hash(json.write(chunk));
    var existing =
        db.queryForList(
            "SELECT payload_hash FROM attempt_chunks WHERE job_id=? AND generation=? AND"
                + " sequence=?",
            job,
            generation,
            chunk.sequence());
    if (!existing.isEmpty()) {
      if (!existing.get(0).get("payload_hash").equals(hash))
        throw new ApiException(409, "Log sequence reused with different content");
      return;
    }
    if (chunk.sequence() != ((Number) row.get("last_chunk")).intValue() + 1)
      throw new ApiException(409, "Log sequence gap");
    int bytes = chunk.text().getBytes(StandardCharsets.UTF_8).length;
    if (((Number) row.get("output_bytes")).intValue() + bytes > 131072)
      throw new ApiException(413, "Job output budget exhausted");
    db.update(
        "INSERT INTO attempt_chunks(job_id,generation,sequence,payload_hash) VALUES(?,?,?,?)",
        job,
        generation,
        chunk.sequence(),
        hash);
    db.update("UPDATE jobs SET output_bytes=output_bytes+? WHERE id=?", bytes, job);
    db.update(
        "UPDATE attempts SET last_chunk=? WHERE job_id=? AND generation=?",
        chunk.sequence(),
        job,
        generation);
    events.emit(
        job,
        "LOG",
        Map.of(
            "generation",
            generation,
            "sequence",
            chunk.sequence(),
            "stream",
            chunk.stream(),
            "text",
            chunk.text()));
  }

  @Transactional
  public void complete(
      String node, UUID worker, UUID job, int generation, Map<String, Object> result) {
    String verdict = Objects.toString(result.get("verdict"), "");
    if (!Set.of(
            "ACCEPTED",
            "WRONG_ANSWER",
            "COMPILE_ERROR",
            "RUNTIME_ERROR",
            "TIME_LIMIT",
            "MEMORY_LIMIT",
            "OUTPUT_LIMIT",
            "INFRA_ERROR")
        .contains(verdict)) throw new ApiException(400, "Invalid verdict");
    String encoded = json.write(result);
    if (encoded.getBytes(StandardCharsets.UTF_8).length > 98304)
      throw new ApiException(413, "Result too large");
    String hash = AuthService.hash(encoded);
    var row = locked(node, worker, job, generation);
    if (row.get("report_hash") != null && row.get("report_hash").equals(hash)) return;
    guard(row, worker, generation);
    if (!row.get("state").equals("RUNNING")) throw new ApiException(409, "Attempt has not started");
    db.update(
        "UPDATE attempts SET result=?::jsonb,report_hash=?,ended_at=clock_timestamp(),state=? WHERE"
            + " job_id=? AND generation=?",
        encoded,
        hash,
        verdict.equals("INFRA_ERROR") ? "LOST" : "COMPLETE",
        job,
        generation);
    if (verdict.equals("INFRA_ERROR")) {
      retryOrFinish(row, job, "INFRA_ERROR");
      return;
    }
    finish(row, job, verdict);
    String language =
        db.queryForObject(
            "SELECT language FROM submissions WHERE id=?", String.class, row.get("submission_id"));
    long duration =
        Math.max(
            1,
            Duration.between(JobService.instant(row.get("attempt_started")), jobs.now())
                .toMillis());
    db.update(
        """
INSERT INTO runtime_history(node_id,language,samples,ewma_ms) VALUES(?,?,1,?)
ON CONFLICT(node_id,language) DO UPDATE SET samples=runtime_history.samples+1,ewma_ms=0.8*runtime_history.ewma_ms+0.2*EXCLUDED.ewma_ms
""",
        node,
        language,
        duration);
    metrics
        .timer("codegrid.attempt.duration", "language", language)
        .record(Duration.ofMillis(duration));
  }

  void retryOrFinish(Map<String, Object> row, UUID job, String reason) {
    int attempts = ((Number) row.get("generation")).intValue();
    Instant now = jobs.now();
    if (RetryPolicy.mayRetry(attempts, now, JobService.instant(row.get("deadline")))) {
      new JobLifecycle()
          .transition(JobState.valueOf(row.get("state").toString()), JobEvent.RETRYABLE_FAILURE);
      Instant ready = now.plus(RetryPolicy.delay(attempts, RandomGenerator.getDefault()));
      db.update(
          "UPDATE jobs SET state='RETRY_WAIT',ready_at=? WHERE id=?", Timestamp.from(ready), job);
      events.emit(
          job,
          "STATUS",
          Map.of("state", "RETRY_WAIT", "reason", reason, "ready_at", ready.toString()));
      metrics.counter("codegrid.jobs.retried").increment();
    } else
      finish(
          row,
          job,
          now.isBefore(JobService.instant(row.get("deadline"))) ? "INFRA_ERROR" : "TIME_LIMIT");
  }

  void finish(Map<String, Object> row, UUID job, String verdict) {
    JobState current = JobState.valueOf(row.get("state").toString());
    JobEvent event =
        !jobs.now().isBefore(JobService.instant(row.get("deadline")))
            ? JobEvent.DEADLINE_REACHED
            : verdict.equals("INFRA_ERROR") ? JobEvent.RETRIES_EXHAUSTED : JobEvent.COMPLETE;
    new JobLifecycle().transition(current, event);
    db.update(
        "UPDATE jobs SET state='FINISHED',verdict=?,finished_at=clock_timestamp() WHERE id=?",
        verdict,
        job);
    events.emit(job, "STATUS", Map.of("state", "FINISHED", "verdict", verdict));
    metrics.counter("codegrid.jobs.completed", "verdict", verdict).increment();
    metrics
        .timer("codegrid.job.latency")
        .record(Duration.between(JobService.instant(row.get("created_at")), jobs.now()));
  }
}
