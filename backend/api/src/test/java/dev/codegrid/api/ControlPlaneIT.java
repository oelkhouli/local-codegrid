package dev.codegrid.api;

import static org.junit.jupiter.api.Assertions.*;

import dev.codegrid.domain.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "codegrid.scheduler-enabled=false",
      "codegrid.admin-password=integration-admin-password-only",
      "codegrid.node-token=integration-node-token-not-for-deployment-123456",
      "codegrid.node-id=local",
      "codegrid.node-cpu=1000",
      "codegrid.node-memory=536870912",
      "codegrid.node-slots=1",
      "management.server.port=0"
    })
class ControlPlaneIT {
  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse(
                  "docker.io/library/postgres:17-bookworm@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0")
              .asCompatibleSubstituteFor("postgres"));

  @Container
  static final GenericContainer<?> redisContainer =
      new GenericContainer<>(
              DockerImageName.parse(
                  "docker.io/library/redis:8-alpine@sha256:becdda6c7f4b3fb42e42fd7f120bbf5c54c4caaaf16f26da24e4563d2c1f0576"))
          .withExposedPorts(6379)
          .withCommand("redis-server", "--requirepass", "integration-redis-password");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", postgres::getJdbcUrl);
    p.add("spring.datasource.username", postgres::getUsername);
    p.add("spring.datasource.password", postgres::getPassword);
    p.add("spring.data.redis.host", redisContainer::getHost);
    p.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
    p.add("spring.data.redis.password", () -> "integration-redis-password");
  }

  @Autowired JobService jobs;
  @Autowired WorkerService workers;
  @Autowired Coordinator coordinator;
  @Autowired AuthService auth;
  @Autowired JdbcTemplate db;
  @Autowired StringRedisTemplate redis;
  @Autowired Json json;
  @Autowired ProblemService problems;
  @LocalServerPort int port;
  AuthService.User alice, bob;

  @BeforeEach
  void reset() {
    db.execute(
        "TRUNCATE"
            + " idempotency,attempt_chunks,events,outbox,attempts,jobs,submissions,runtime_history,workers"
            + " RESTART IDENTITY CASCADE");
    db.update("DELETE FROM nodes WHERE id<>'local'");
    db.update(
        "UPDATE nodes SET heartbeat=clock_timestamp(),quarantined=false,load=0 WHERE id='local'");
    db.update("DELETE FROM users WHERE username<>'admin'");
    try (var connection = redis.getConnectionFactory().getConnection()) {
      connection.serverCommands().flushDb();
    }
    alice = user("alice");
    bob = user("bob");
  }

  AuthService.User user(String name) {
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO users(id,username,password_hash,role) VALUES(?,?,?,'USER')",
        id,
        name,
        "unused-service-test-hash");
    return new AuthService.User(id, name, "USER", "unused", "unused");
  }

  JobService.Submission source() {
    return new JobService.Submission(
        Language.PYTHON, "print(42)\n", List.of(new JobService.CaseSpec("", "42\n")), "RUN");
  }

  UUID submit() {
    return (UUID) jobs.submit(alice, UUID.randomUUID().toString(), source()).get("id");
  }

  UUID worker(String node) {
    UUID id = UUID.randomUUID();
    workers.register(
        node, new WorkerService.Registration(id, Map.of("PYTHON", "sha256:" + "a".repeat(64))));
    return id;
  }

  UUID assigned(UUID job) {
    return db.queryForObject("SELECT worker_id FROM jobs WHERE id=?", UUID.class, job);
  }

  Map<String, Object> result() {
    return Map.of("verdict", "ACCEPTED", "cases", List.of());
  }

  @Test
  void parallelIdenticalSubmissionsCreateOneJob() throws Exception {
    String key = UUID.randomUUID().toString();
    var pool = Executors.newFixedThreadPool(16);
    try {
      List<Future<Map<String, Object>>> futures = new ArrayList<>();
      for (int i = 0; i < 50; i++)
        futures.add(pool.submit(() -> jobs.submit(alice, key, source())));
      Set<Object> ids = new HashSet<>();
      for (var f : futures) ids.add(f.get(30, TimeUnit.SECONDS).get("id"));
      assertEquals(1, ids.size());
      assertEquals(1, db.queryForObject("SELECT count(*) FROM jobs", Integer.class));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void changedPayloadConflictsWithTheSameKey() {
    String key = UUID.randomUUID().toString();
    jobs.submit(alice, key, source());
    var changed = new JobService.Submission(Language.PYTHON, "print(43)", source().tests(), "RUN");
    assertEquals(
        409, assertThrows(ApiException.class, () -> jobs.submit(alice, key, changed)).status);
  }

  @Test
  void ownerChecksHideOtherUsersJobs() {
    UUID job = submit();
    assertEquals(404, assertThrows(ApiException.class, () -> jobs.get(bob, job)).status);
    assertTrue(jobs.list(bob).isEmpty());
  }

  @Test
  void challengeCatalogKeepsHiddenTestsOnTheWorkerSide() {
    var catalog = problems.list();
    assertEquals(5, catalog.size());
    var fizzBuzz =
        catalog.stream().filter(p -> p.slug().equals("fizz-buzz")).findFirst().orElseThrow();
    assertEquals(1, fizzBuzz.publicTests().size());
    assertTrue(fizzBuzz.starterCode().containsKey("JAVA"));

    var forged = List.of(new JobService.CaseSpec("1\n", "forged\n"));
    var request =
        new JobService.Submission(
            Language.PYTHON, "print('candidate')\n", forged, "RUN", "fizz-buzz");
    UUID job = (UUID) jobs.submit(alice, UUID.randomUUID().toString(), request).get("id");

    var publicSubmission = (Map<?, ?>) jobs.get(alice, job).get("submission");
    assertEquals("fizz-buzz", publicSubmission.get("problem"));
    assertEquals(1, ((List<?>) publicSubmission.get("tests")).size());
    assertFalse(json.write(publicSubmission).contains("FizzBuzz\\n16"));

    worker("local");
    assertTrue(coordinator.dispatch());
    var assignment = workers.assignment("local", assigned(job));
    assertEquals(4, ((List<?>) assignment.get("tests")).size());
    assertFalse(json.write(assignment.get("tests")).contains("forged"));
  }

  @Test
  void concurrentSchedulersShareTheNodeBudget() throws Exception {
    worker("local");
    worker("local");
    submit();
    submit();
    var pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<Boolean>> work = new ArrayList<>();
      for (int i = 0; i < 100; i++) work.add(pool.submit(coordinator::dispatch));
      for (var f : work) f.get(30, TimeUnit.SECONDS);
      assertEquals(
          1, db.queryForObject("SELECT count(*) FROM attempts WHERE NOT cleaned", Integer.class));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void activeAttemptCannotPrematurelyReleaseCapacity() {
    worker("local");
    UUID job = submit();
    assertTrue(coordinator.dispatch());
    assertEquals(
        409, assertThrows(ApiException.class, () -> workers.cleaned("local", job, 1)).status);
  }

  @Test
  void expiryKeepsPhysicalReservationAndFencesLateResults() {
    worker("local");
    UUID job = submit();
    coordinator.dispatch();
    UUID first = assigned(job);
    workers.renew("local", first, job, 1, true);
    db.update(
        "UPDATE attempts SET lease_until=clock_timestamp()-interval '1 second' WHERE job_id=?",
        job);
    assertEquals(
        409,
        assertThrows(ApiException.class, () -> workers.renew("local", first, job, 1, false))
            .status);
    coordinator.reap();
    assertEquals(
        "RETRY_WAIT", db.queryForObject("SELECT state FROM jobs WHERE id=?", String.class, job));
    assertEquals(
        false,
        db.queryForObject("SELECT cleaned FROM attempts WHERE job_id=?", Boolean.class, job));
    db.update("UPDATE jobs SET ready_at=clock_timestamp()-interval '1 second' WHERE id=?", job);
    assertFalse(coordinator.dispatch());
    db.update(
        "INSERT INTO nodes(id,token_hash,cpu_budget,memory_budget,slots,heartbeat,quarantined)"
            + " VALUES('second',?,1000,536870912,1,clock_timestamp(),false)",
        "b".repeat(64));
    worker("second");
    assertTrue(coordinator.dispatch());
    UUID second = assigned(job);
    assertEquals(
        2, db.queryForObject("SELECT generation FROM jobs WHERE id=?", Integer.class, job));
    workers.renew("second", second, job, 2, true);
    assertEquals(
        409,
        assertThrows(ApiException.class, () -> workers.complete("local", first, job, 1, result()))
            .status);
    workers.complete("second", second, job, 2, result());
    long seq = db.queryForObject("SELECT event_seq FROM jobs WHERE id=?", Long.class, job);
    workers.complete("second", second, job, 2, result());
    assertEquals(seq, db.queryForObject("SELECT event_seq FROM jobs WHERE id=?", Long.class, job));
    workers.cleaned("local", job, 1);
    assertEquals(
        "ACCEPTED", db.queryForObject("SELECT verdict FROM jobs WHERE id=?", String.class, job));
  }

  @Test
  void logDeliveryIsOrderedAndIdempotent() {
    worker("local");
    UUID job = submit();
    coordinator.dispatch();
    UUID worker = assigned(job);
    workers.renew("local", worker, job, 1, true);
    var one = new WorkerService.LogChunk(1, "stdout", "hello\n");
    workers.log("local", worker, job, 1, one);
    workers.log("local", worker, job, 1, one);
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM events WHERE job_id=? AND kind='LOG'", Integer.class, job));
    assertEquals(
        409,
        assertThrows(
                ApiException.class,
                () ->
                    workers.log(
                        "local",
                        worker,
                        job,
                        1,
                        new WorkerService.LogChunk(1, "stdout", "different")))
            .status);
    assertEquals(
        409,
        assertThrows(
                ApiException.class,
                () ->
                    workers.log(
                        "local", worker, job, 1, new WorkerService.LogChunk(3, "stdout", "gap")))
            .status);
  }

  @Test
  void duplicateNotificationsDoNotCreateNewAttempts() {
    UUID worker = worker("local");
    UUID job = submit();
    coordinator.dispatch();
    coordinator.publish();
    db.update("UPDATE outbox SET published_at=NULL");
    coordinator.publish();
    assertEquals(2, redis.opsForList().size("assign:" + worker));
    assertEquals(job, workers.assignment("local", worker).get("id"));
    assertEquals(job, workers.assignment("local", worker).get("id"));
    assertEquals(1, db.queryForObject("SELECT count(*) FROM attempts", Integer.class));
  }

  @Test
  void cancellationAndCompletionHaveOneTerminalWinner() throws Exception {
    worker("local");
    UUID job = submit();
    coordinator.dispatch();
    UUID worker = assigned(job);
    workers.renew("local", worker, job, 1, true);
    var pool = Executors.newFixedThreadPool(2);
    var gate = new CountDownLatch(1);
    try {
      var a =
          pool.submit(
              () -> {
                gate.await();
                jobs.cancel(alice, job);
                return true;
              });
      var b =
          pool.submit(
              () -> {
                gate.await();
                try {
                  workers.complete("local", worker, job, 1, result());
                } catch (ApiException e) {
                  assertEquals(409, e.status);
                }
                return true;
              });
      gate.countDown();
      a.get(20, TimeUnit.SECONDS);
      b.get(20, TimeUnit.SECONDS);
      assertEquals(
          1,
          db.queryForObject(
              "SELECT count(*) FROM events WHERE job_id=? AND kind='STATUS' AND data->>'state' IN"
                  + " ('FINISHED','CANCELLED')",
              Integer.class,
              job));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void retryBudgetTerminatesARepeatedlyLostJob() {
    worker("local");
    UUID job = submit();
    for (int attempt = 1; attempt <= 3; attempt++) {
      db.update("UPDATE jobs SET ready_at=clock_timestamp()-interval '1 second' WHERE id=?", job);
      assertTrue(coordinator.dispatch());
      db.update(
          "UPDATE attempts SET lease_until=clock_timestamp()-interval '1 second' WHERE job_id=? AND"
              + " generation=?",
          job,
          attempt);
      coordinator.reap();
      workers.cleaned("local", job, attempt);
      workers.nodeHeartbeat("local", 0);
    }
    assertEquals(
        "FINISHED", db.queryForObject("SELECT state FROM jobs WHERE id=?", String.class, job));
    assertEquals(
        "INFRA_ERROR", db.queryForObject("SELECT verdict FROM jobs WHERE id=?", String.class, job));
    assertEquals(3, db.queryForObject("SELECT count(*) FROM attempts", Integer.class));
  }

  @Test
  void realHttpRequiresSessionCsrfOriginAndNodeCredentials() throws Exception {
    var client = HttpClient.newHttpClient();
    String base = "http://127.0.0.1:" + port;
    var login =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"admin\",\"password\":\"integration-admin-password-only\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, login.statusCode());
    String cookie = login.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    var data = (Map<?, ?>) json.read(login.body());
    String csrf = data.get("csrf").toString();
    var missing =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/jobs"))
                .header("Cookie", cookie)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.write(source())))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(403, missing.statusCode());
    var origin =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/jobs"))
                .header("Cookie", cookie)
                .header("Origin", "https://example.invalid")
                .header("X-CSRF-Token", csrf)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(403, origin.statusCode());
    assertEquals(
        401,
        client
            .send(
                HttpRequest.newBuilder(URI.create(base + "/internal/cleanup")).GET().build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(
        401,
        client
            .send(
                HttpRequest.newBuilder(URI.create(base + "/api/jobs")).GET().build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
  }
}
