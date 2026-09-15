package dev.codegrid.api;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ResourceMetrics {
  public ResourceMetrics(JdbcTemplate db, MeterRegistry meters) {
    meters.gauge(
        "codegrid.reservations.slots",
        db,
        x -> count(x, "SELECT count(*) FROM attempts WHERE NOT cleaned"));
    meters.gauge(
        "codegrid.reservations.memory.bytes",
        db,
        x -> 536870912L * count(x, "SELECT count(*) FROM attempts WHERE NOT cleaned"));
    meters.gauge(
        "codegrid.reservations.cpu.millicores",
        db,
        x -> 1000 * count(x, "SELECT count(*) FROM attempts WHERE NOT cleaned"));
    meters.gauge(
        "codegrid.jobs.queued",
        db,
        x -> count(x, "SELECT count(*) FROM jobs WHERE state IN ('QUEUED','RETRY_WAIT')"));
    meters.gauge(
        "codegrid.nodes.quarantined",
        db,
        x -> count(x, "SELECT count(*) FROM nodes WHERE quarantined"));
    meters.gauge(
        "codegrid.outbox.pending",
        db,
        x -> count(x, "SELECT count(*) FROM outbox WHERE published_at IS NULL"));
  }

  private static double count(JdbcTemplate db, String sql) {
    try {
      return db.queryForObject(sql, Long.class);
    } catch (Exception e) {
      return Double.NaN;
    }
  }
}
