package dev.codegrid.api;

import dev.codegrid.domain.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class Coordinator {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(Coordinator.class);
  private final java.util.concurrent.atomic.AtomicLong lastErrorLog =
      new java.util.concurrent.atomic.AtomicLong();

  private void report(Exception error) {
    long now = System.nanoTime(), previous = lastErrorLog.get();
    if (now - previous > 10_000_000_000L && lastErrorLog.compareAndSet(previous, now)) {
      // Frame names identify failures without printing SQL parameters, tokens or submitted source.
      String frames =
          Arrays.stream(error.getStackTrace())
              .filter(f -> f.getClassName().startsWith("dev.codegrid."))
              .limit(4)
              .map(Object::toString)
              .collect(java.util.stream.Collectors.joining("; "));
      LOG.warn("coordinator_failure type={} frames={}", error.getClass().getSimpleName(), frames);
    }
  }

  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final WorkerService workers;
  private final Events events;
  private final Json json;
  private final MeterRegistry metrics;
  private final StringRedisTemplate redis;
  private final boolean enabled;

  public Coordinator(
      JdbcTemplate db,
      TransactionTemplate tx,
      WorkerService workers,
      Events events,
      Json json,
      MeterRegistry metrics,
      StringRedisTemplate redis,
      @Value("${codegrid.scheduler-enabled}") boolean enabled) {
    this.db = db;
    this.tx = tx;
    this.workers = workers;
    this.events = events;
    this.json = json;
    this.metrics = metrics;
    this.redis = redis;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelay = 500)
  public void tick() {
    if (!enabled) return;
    try {
      reap();
      for (int i = 0; i < 8; i++) if (!dispatch()) break;
    } catch (Exception e) {
      metrics.counter("codegrid.coordinator.errors").increment();
      report(e);
    }
  }

  public void reap() {
    tx.executeWithoutResult(
        s -> {
          var rows =
              db.queryForList(
                  """
SELECT j.* FROM jobs j WHERE j.state NOT IN ('FINISHED','CANCELLED') AND
  (j.deadline<=clock_timestamp() OR EXISTS(SELECT 1 FROM attempts a WHERE a.job_id=j.id AND a.generation=j.generation
    AND a.state IN ('LEASED','RUNNING') AND (a.lease_until<=clock_timestamp() OR a.deadline<=clock_timestamp())))
ORDER BY j.created_at FOR UPDATE OF j SKIP LOCKED LIMIT 32
""");
          for (var row : rows) {
            UUID id = (UUID) row.get("id");
            db.update(
                "UPDATE attempts SET state='LOST',ended_at=clock_timestamp() WHERE job_id=? AND"
                    + " state IN ('LEASED','RUNNING')",
                id);
            db.update(
                "UPDATE nodes SET quarantined=true WHERE id IN (SELECT node_id FROM attempts WHERE"
                    + " job_id=? AND NOT cleaned)",
                id);
            Instant now =
                db.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
            if (!now.isBefore(JobService.instant(row.get("deadline"))))
              workers.finish(row, id, "TIME_LIMIT");
            else workers.retryOrFinish(row, id, "LEASE_EXPIRED");
          }
        });
  }

  public boolean dispatch() {
    return Boolean.TRUE.equals(
        tx.execute(
            status -> {
              if (!Boolean.TRUE.equals(
                  db.queryForObject("SELECT pg_try_advisory_xact_lock(74001)", Boolean.class)))
                return false;
              var jobs =
                  db.queryForList(
                      """
SELECT j.*,s.language FROM jobs j JOIN submissions s ON s.id=j.submission_id
WHERE j.state IN ('QUEUED','RETRY_WAIT') AND j.ready_at<=clock_timestamp() AND j.deadline>clock_timestamp()
ORDER BY j.created_at FOR UPDATE OF j SKIP LOCKED LIMIT 32
""");
              for (var job : jobs) {
                Language language = Language.valueOf(job.get("language").toString());
                var options = candidates(language);
                var choice = SchedulingPolicy.choose(options);
                if (choice.isEmpty()) continue;
                var chosen = choice.get();
                db.queryForMap("SELECT id FROM nodes WHERE id=? FOR UPDATE", chosen.node());
                // Recompute after acquiring the shared physical-node budget lock.
                choice =
                    SchedulingPolicy.choose(
                        candidates(language).stream()
                            .filter(c -> c.worker().equals(chosen.worker()))
                            .toList());
                if (choice.isEmpty()) continue;
                new JobLifecycle()
                    .transition(JobState.valueOf(job.get("state").toString()), JobEvent.ASSIGN);
                UUID id = (UUID) job.get("id"), worker = UUID.fromString(chosen.worker());
                int generation = ((Number) job.get("generation")).intValue() + 1;
                var images =
                    (Map<?, ?>)
                        json.read(
                            db.queryForObject(
                                "SELECT images::text FROM workers WHERE id=?",
                                String.class,
                                worker));
                String image = images.get(language.name()).toString();
                db.update(
                    "UPDATE jobs SET state='LEASED',worker_id=?,generation=? WHERE id=?",
                    worker,
                    generation,
                    id);
                db.update(
                    """
INSERT INTO attempts(job_id,generation,worker_id,node_id,state,lease_until,deadline,expected_ms,image_id)
VALUES(?,?,?,?,'LEASED',LEAST(clock_timestamp()+interval '15 seconds',?),LEAST(clock_timestamp()+interval '90 seconds',?),?,?)
""",
                    id,
                    generation,
                    worker,
                    chosen.node(),
                    job.get("deadline"),
                    job.get("deadline"),
                    chosen.expectedMs(),
                    image);
                db.update(
                    "INSERT INTO outbox(worker_id,job_id,generation) VALUES(?,?,?)",
                    worker,
                    id,
                    generation);
                events.emit(
                    id,
                    "STATUS",
                    Map.of(
                        "state",
                        "LEASED",
                        "generation",
                        generation,
                        "worker",
                        worker,
                        "node",
                        chosen.node(),
                        "expected_ms",
                        chosen.expectedMs(),
                        "score",
                        chosen.score()));
                metrics
                    .timer("codegrid.job.queue")
                    .record(
                        Duration.between(
                            JobService.instant(job.get("ready_at")),
                            db.queryForObject("SELECT clock_timestamp()", Timestamp.class)
                                .toInstant()));
                return true;
              }
              return false;
            }));
  }

  private List<SchedulingPolicy.Candidate> candidates(Language language) {
    var rows =
        db.queryForList(
            """
SELECT w.id,w.node_id,n.cpu_budget,n.memory_budget,n.slots,n.load,
  COALESCE(h.ewma_ms,? / n.speed) AS expected_ms,
  (SELECT count(*) FROM attempts a WHERE a.node_id=n.id AND NOT a.cleaned) AS used
FROM workers w JOIN nodes n ON n.id=w.node_id LEFT JOIN runtime_history h ON h.node_id=n.id AND h.language=?
WHERE NOT n.quarantined AND n.heartbeat>clock_timestamp()-interval '10 seconds'
  AND w.heartbeat>clock_timestamp()-interval '10 seconds' AND w.languages @> ?::jsonb
  AND NOT EXISTS(SELECT 1 FROM attempts a WHERE a.worker_id=w.id AND NOT a.cleaned)
""",
            language.coldEstimateMs,
            language.name(),
            json.write(List.of(language.name())));
    return rows.stream()
        .map(
            r -> {
              int used = ((Number) r.get("used")).intValue();
              return new SchedulingPolicy.Candidate(
                  r.get("id").toString(),
                  r.get("node_id").toString(),
                  Math.max(1, ((Number) r.get("expected_ms")).longValue()),
                  ((Number) r.get("load")).doubleValue(),
                  ((Number) r.get("cpu_budget")).intValue(),
                  used * 1000,
                  ((Number) r.get("memory_budget")).longValue(),
                  used * 536870912L,
                  ((Number) r.get("slots")).intValue(),
                  used,
                  true,
                  true);
            })
        .toList();
  }

  @Scheduled(fixedDelay = 500)
  public void publishTick() {
    if (enabled) publish();
  }

  public void publish() {
    try {
      tx.executeWithoutResult(
          s -> {
            for (var row :
                db.queryForList(
                    "SELECT * FROM outbox WHERE published_at IS NULL ORDER BY id FOR UPDATE SKIP"
                        + " LOCKED LIMIT 32")) {
              String key = "assign:" + row.get("worker_id");
              redis.opsForList().rightPush(key, row.get("job_id") + ":" + row.get("generation"));
              redis.opsForList().trim(key, -64, -1);
              redis.expire(key, Duration.ofMinutes(5));
              db.update(
                  "UPDATE outbox SET published_at=clock_timestamp() WHERE id=?", row.get("id"));
            }
          });
    } catch (Exception e) {
      metrics.counter("codegrid.outbox.failures").increment();
      report(e);
    }
  }

  @Scheduled(fixedDelay = 60000)
  public void maintenance() {
    if (!enabled) return;
    try {
      db.update("DELETE FROM sessions WHERE expires_at<clock_timestamp()");
      db.update("DELETE FROM outbox WHERE published_at<clock_timestamp()-interval '1 day'");
      db.update(
          "DELETE FROM audit_log WHERE id IN (SELECT id FROM audit_log WHERE"
              + " created_at<clock_timestamp()-interval '30 days' LIMIT 1000)");
    } catch (Exception e) {
      metrics.counter("codegrid.coordinator.errors").increment();
      report(e);
    }
  }
}
