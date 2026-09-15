package dev.codegrid.api;

import dev.codegrid.domain.Language;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProblemService {
  public record Problem(
      String slug,
      String title,
      String difficulty,
      String description,
      Map<String, String> starterCode,
      List<JobService.CaseSpec> publicTests) {}

  public record Selection(
      String slug,
      String title,
      String starter,
      List<JobService.CaseSpec> tests,
      int publicCount) {}

  private final JdbcTemplate db;
  private final Json json;

  public ProblemService(JdbcTemplate db, Json json) {
    this.db = db;
    this.json = json;
  }

  public List<Problem> list() {
    return db.query(
        "SELECT slug,title,difficulty,description,starter_code::text,public_tests::text FROM"
            + " problems WHERE enabled ORDER BY CASE difficulty WHEN 'EASY' THEN 1 WHEN 'MEDIUM'"
            + " THEN 2 ELSE 3 END,title",
        (row, ignored) ->
            new Problem(
                row.getString("slug"),
                row.getString("title"),
                row.getString("difficulty"),
                row.getString("description"),
                stringMap(row.getString("starter_code")),
                cases(row.getString("public_tests"))));
  }

  public Selection select(String slug, Language language) {
    if (slug == null || !slug.matches("[a-z0-9-]{1,64}"))
      throw new ApiException(400, "Invalid challenge");
    var rows =
        db.queryForList(
            "SELECT title,starter_code::text,public_tests::text,hidden_tests::text FROM problems"
                + " WHERE slug=? AND enabled",
            slug);
    if (rows.isEmpty()) throw new ApiException(404, "Challenge not found");
    var row = rows.get(0);
    Map<String, String> starters = stringMap(row.get("starter_code").toString());
    String starter = starters.get(language.name());
    if (starter == null) throw new ApiException(400, "Challenge does not support this language");
    List<JobService.CaseSpec> visible = cases(row.get("public_tests").toString());
    List<JobService.CaseSpec> all = new ArrayList<>(visible);
    all.addAll(cases(row.get("hidden_tests").toString()));
    return new Selection(
        slug, row.get("title").toString(), starter, List.copyOf(all), visible.size());
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> stringMap(String value) {
    Map<?, ?> raw = (Map<?, ?>) json.read(value);
    Map<String, String> result = new LinkedHashMap<>();
    raw.forEach((key, item) -> result.put(key.toString(), item.toString()));
    return Map.copyOf(result);
  }

  private List<JobService.CaseSpec> cases(String value) {
    List<?> raw = (List<?>) json.read(value);
    return raw.stream()
        .map(
            item -> {
              Map<?, ?> test = (Map<?, ?>) item;
              return new JobService.CaseSpec(
                  Objects.toString(test.get("input"), ""),
                  Objects.toString(test.get("expected"), ""));
            })
        .toList();
  }
}
