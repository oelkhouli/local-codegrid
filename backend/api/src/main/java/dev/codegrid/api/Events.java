package dev.codegrid.api;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class Events {
  private final JdbcTemplate db;
  private final Json json;

  public Events(JdbcTemplate db, Json json) {
    this.db = db;
    this.json = json;
  }

  /** Caller owns the job row lock and transaction: sequence and payload commit together. */
  public void emit(UUID job, String kind, Object data) {
    Long seq =
        db.queryForObject(
            "UPDATE jobs SET event_seq=event_seq+1 WHERE id=? RETURNING event_seq",
            Long.class,
            job);
    db.update(
        "INSERT INTO events(job_id,seq,kind,data) VALUES(?,?,?,?::jsonb)",
        job,
        seq,
        kind,
        json.write(data));
  }

  public List<Map<String, Object>> after(UUID job, long seq) {
    var result =
        db.queryForList(
            "SELECT seq,kind,data,created_at FROM events WHERE job_id=? AND seq>? ORDER BY seq"
                + " LIMIT 128",
            job,
            seq);
    result.forEach(r -> r.put("data", json.read(r.get("data").toString())));
    return result;
  }
}
