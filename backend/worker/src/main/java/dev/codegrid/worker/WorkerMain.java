package dev.codegrid.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.codegrid.domain.Language;
import io.micrometer.core.instrument.binder.jvm.*;
import io.micrometer.prometheusmetrics.*;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class WorkerMain {
  private final String node = env("NODE_ID", "local"), mode = env("WORKER_MODE", "worker");
  private final UUID worker = UUID.randomUUID();
  private final ControlClient api =
      new ControlClient(env("API_URL", "http://api:8080"), node, required("NODE_TOKEN"));
  private final RuntimeEngine runtime = new RuntimeEngine(env("CODEGRID_ENGINE", "docker"), node);
  private final NodeLedger ledger;
  private final PrometheusMeterRegistry metrics =
      new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
  private final AtomicInteger active = new AtomicInteger();
  private volatile boolean ready = false;
  private final Map<String, String> images = new LinkedHashMap<>();
  private final ScheduledExecutorService pulses = Executors.newScheduledThreadPool(2);
  private int slots, cpu;
  private long memory;

  public WorkerMain() throws Exception {
    ledger = new NodeLedger(Path.of(env("LEDGER_PATH", "/ledger")));
  }

  public static void main(String[] args) throws Exception {
    new WorkerMain().start();
  }

  static String env(String key, String fallback) {
    return System.getenv().getOrDefault(key, fallback);
  }

  static String required(String key) {
    String value = System.getenv(key);
    if (value == null || value.length() < 32)
      throw new IllegalStateException(key + " must be configured");
    return value;
  }

  private void start() throws Exception {
    new JvmMemoryMetrics().bindTo(metrics);
    new JvmThreadMetrics().bindTo(metrics);
    metrics.gauge("codegrid.worker.active", active);
    metrics.gauge(
        "codegrid.worker.system.load",
        this,
        w -> Math.max(0, ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage()));
    HttpServer server =
        HttpServer.create(
            new InetSocketAddress(
                env("METRICS_BIND", "0.0.0.0"), Integer.parseInt(env("METRICS_PORT", "9102"))),
            16);
    server.createContext(
        "/health",
        e -> {
          byte[] b = (ready ? "ready" : "starting").getBytes(StandardCharsets.UTF_8);
          e.sendResponseHeaders(ready ? 200 : 503, b.length);
          try (var out = e.getResponseBody()) {
            out.write(b);
          }
        });
    server.createContext(
        "/metrics",
        e -> {
          byte[] b = metrics.scrape().getBytes(StandardCharsets.UTF_8);
          e.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
          e.sendResponseHeaders(200, b.length);
          try (var out = e.getResponseBody()) {
            out.write(b);
          }
        });
    server.start();
    JsonNode config = api.get("/internal/config");
    slots = config.path("slots").asInt();
    cpu = config.path("cpu_budget").asInt();
    memory = config.path("memory_budget").asLong();
    String ledgerId = ledger.identity().toString();
    api.post("/internal/bind-ledger", Map.of("ledger", ledgerId));
    api.ledger(ledgerId);
    var info = runtime.info();
    long machineMemory = info.path("MemTotal").asLong();
    int machineCpu = info.path("NCPU").asInt();
    if (machineMemory == 0
        || machineCpu == 0
        || memory > machineMemory - 536870912L
        || cpu > machineCpu * 1000)
      throw new IllegalStateException("Node budget exceeds runtime host capacity");
    if (mode.equals("reaper")) {
      for (; ; ) {
        try {
          reconcile();
          ready = true;
        } catch (Exception e) {
          ready = false;
          log("cleanup_error", e);
        }
        Thread.sleep(1000);
      }
    }
    if (!mode.equals("worker")) throw new IllegalArgumentException("Unknown worker mode");
    for (Language language : Language.values()) {
      String image = runtime.image(language.image());
      preflight(image);
      images.put(language.name(), image);
    }
    api.post("/internal/register", Map.of("worker", worker, "images", images));
    ready = true;
    pulses.scheduleWithFixedDelay(
        () -> {
          try {
            api.post("/internal/heartbeat?worker=" + worker, Map.of());
          } catch (Exception e) {
            log("heartbeat_error", e);
          }
        },
        1,
        3,
        TimeUnit.SECONDS);
    System.out.println(
        "{\"event\":\"worker_ready\",\"worker\":\"" + worker + "\",\"node\":\"" + node + "\"}");
    for (; ; ) {
      try {
        var assignment = api.get("/internal/assignment?worker=" + worker);
        if (assignment.has("id")) execute(assignment);
      } catch (Exception e) {
        log("poll_error", e);
      }
      Thread.sleep(700);
    }
  }

  private void preflight(String image) throws Exception {
    UUID id = UUID.randomUUID();
    String name = SandboxPolicy.name(id, 1, 0);
    try {
      runtime.create(id, 1, 0, System.currentTimeMillis() + 30000, image);
      var result =
          runtime.run(
              name,
              Map.of("probe", true),
              System.currentTimeMillis() + 25000,
              new AtomicInteger(),
              (s, t) -> {});
      if (result.exit() != 0 || result.timedOut())
        throw new IllegalStateException(
            "Sandbox enforcement probe failed: "
                + new String(result.stdout(), StandardCharsets.UTF_8)
                + " "
                + new String(result.stderr(), StandardCharsets.UTF_8));
      var checks = ControlClient.JSON.readTree(result.stdout());
      if (checks.size() != 13) throw new IllegalStateException("Incomplete enforcement probe");
      for (var value : checks)
        if (!value.asBoolean())
          throw new IllegalStateException("Required sandbox restriction is unavailable");
    } finally {
      runtime.remove(name);
    }
  }

  private String path(UUID job, int generation, String action) {
    return "/internal/jobs/" + job + "/" + generation + "/" + action + "?worker=" + worker;
  }

  private void execute(JsonNode a) throws Exception {
    UUID job = UUID.fromString(a.path("id").asText());
    int generation = a.path("generation").asInt();
    long deadline = Instant.parse(a.path("deadline").asText()).toEpochMilli();
    String image = a.path("image_id").asText();
    Language language = Language.valueOf(a.path("language").asText());
    if (!image.equals(images.get(language.name())))
      throw new IllegalStateException("Assignment image differs from registered runtime");
    ledger.begin(job, generation, deadline, slots, memory, cpu);
    active.incrementAndGet();
    long attemptStarted = System.nanoTime();
    AtomicBoolean lost = new AtomicBoolean();
    AtomicInteger budget = new AtomicInteger(), displayBudget = new AtomicInteger(8192);
    var queue = new ArrayBlockingQueue<Map<String, String>>(64);
    AtomicBoolean loggingDone = new AtomicBoolean();
    Thread logger = new Thread(() -> sendLogs(job, generation, queue, loggingDone, lost));
    logger.setDaemon(true);
    logger.start();
    ScheduledFuture<?> renewal = null;
    List<Map<String, Object>> cases = new ArrayList<>();
    String verdict = "ACCEPTED";
    try {
      api.post(path(job, generation, "renew") + "&start=true", Map.of());
      renewal =
          pulses.scheduleWithFixedDelay(
              () -> {
                try {
                  api.post(path(job, generation, "renew"), Map.of());
                } catch (Exception e) {
                  lost.set(true);
                }
              },
              2,
              3,
              TimeUnit.SECONDS);
      int repetitions = a.path("mode").asText().equals("BENCHMARK") ? 3 : 1, index = 0;
      outer:
      for (int repeat = 0; repeat < repetitions; repeat++)
        for (int test = 0; test < a.path("tests").size(); test++) {
          if (lost.get() || System.nanoTime() - attemptStarted >= 90_000_000_000L)
            throw new IllegalStateException("Lease or local monotonic deadline expired");
          final int current = index++;
          ledger.locked(
              () -> {
                ledger.allowed(job, generation);
                api.post(path(job, generation, "renew"), Map.of());
                runtime.create(job, generation, current, deadline, image);
                return null;
              });
          String name = SandboxPolicy.name(job, generation, current);
          var input = a.path("tests").get(test);
          RuntimeEngine.Outcome result;
          try {
            result =
                runtime.run(
                    name,
                    Map.of(
                        "language",
                        language.name(),
                        "source",
                        a.path("source").asText(),
                        "input",
                        input.path("input").asText()),
                    Math.min(
                        deadline,
                        System.currentTimeMillis()
                            + Math.max(
                                0,
                                90000
                                    - TimeUnit.NANOSECONDS.toMillis(
                                        System.nanoTime() - attemptStarted))),
                    budget,
                    (stream, text) -> {
                      // Bound the queue. A slow/disconnected control plane must stop execution
                      // instead of growing memory.
                      try {
                        if (!queue.offer(
                            Map.of("stream", stream, "text", text), 500, TimeUnit.MILLISECONDS))
                          lost.set(true);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        lost.set(true);
                      }
                    });
          } finally {
            runtime.remove(name);
          }
          String outcome = verdict(result, input.path("expected").asText());
          Map<String, Object> c = new LinkedHashMap<>();
          c.put("case", test + 1);
          c.put("repetition", repeat + 1);
          c.put("verdict", outcome);
          c.put("elapsed_ms", result.elapsedMs());
          c.put("stdout", display(result.stdout(), displayBudget));
          c.put("stderr", display(result.stderr(), displayBudget));
          c.put("sampled_memory_bytes", result.sampledMemoryBytes());
          cases.add(c);
          io.micrometer.core.instrument.Timer.builder("codegrid.sandbox.duration")
              .tags("language", language.name(), "verdict", outcome)
              .publishPercentileHistogram()
              .register(metrics)
              .record(Duration.ofMillis(result.elapsedMs()));
          if (!outcome.equals("ACCEPTED")) {
            verdict = outcome;
            break outer;
          }
        }
    } catch (Exception e) {
      verdict = "INFRA_ERROR";
      log("attempt_interrupted", e);
    } finally {
      loggingDone.set(true);
      logger.join(6000);
      if (logger.isAlive()) {
        lost.set(true);
        logger.interrupt();
      }
      if (lost.get()) verdict = "INFRA_ERROR";
      Map<String, Object> report =
          Map.of(
              "verdict",
              verdict,
              "cases",
              cases,
              "measurement",
              "cold_compile_and_run",
              "output_bytes",
              Math.min(32768, budget.get()));
      for (int i = 0; i < 3; i++)
        try {
          api.post(path(job, generation, "complete"), report);
          break;
        } catch (ControlClient.Rejected e) {
          break;
        } catch (Exception e) {
          Thread.sleep(250L * (i + 1));
        }
      if (renewal != null) renewal.cancel(false);
      try {
        retire(job, generation);
      } finally {
        active.decrementAndGet();
      }
    }
  }

  private void sendLogs(
      UUID job,
      int generation,
      BlockingQueue<Map<String, String>> queue,
      AtomicBoolean done,
      AtomicBoolean lost) {
    int seq = 0;
    try {
      while (!done.get() || !queue.isEmpty()) {
        var piece = queue.poll(200, TimeUnit.MILLISECONDS);
        if (piece == null) continue;
        var payload =
            Map.of("sequence", ++seq, "stream", piece.get("stream"), "text", piece.get("text"));
        boolean sent = false;
        for (int i = 0; i < 3; i++)
          try {
            api.post(path(job, generation, "logs"), payload);
            sent = true;
            break;
          } catch (ControlClient.Rejected e) {
            throw e;
          } catch (Exception e) {
            Thread.sleep(200);
          }
        if (!sent) throw new IllegalStateException("Cannot persist live output");
      }
    } catch (Exception e) {
      lost.set(true);
    }
  }

  static String verdict(RuntimeEngine.Outcome r, String expected) {
    if (r.outputLimited()) return "OUTPUT_LIMIT";
    if (r.oom() || r.exit() == 22) return "MEMORY_LIMIT";
    if (r.timedOut() || r.exit() == 124 || r.exit() == 24) return "TIME_LIMIT";
    if (r.exit() == 20) return "COMPILE_ERROR";
    if (r.exit() == 30 || r.exit() == 125) return "INFRA_ERROR";
    if (r.exit() != 0) return "RUNTIME_ERROR";
    return Arrays.equals(
            normalize(r.stdout()), normalize(expected.getBytes(StandardCharsets.UTF_8)))
        ? "ACCEPTED"
        : "WRONG_ANSWER";
  }

  static String display(byte[] bytes, AtomicInteger remaining) {
    String text = new String(bytes, StandardCharsets.UTF_8);
    int count = Math.min(remaining.get(), text.length());
    remaining.addAndGet(-count);
    return text.substring(0, count);
  }

  static byte[] normalize(byte[] bytes) {
    var out = new java.io.ByteArrayOutputStream();
    for (int i = 0; i < bytes.length; i++) {
      if (bytes[i] == '\r' && i + 1 < bytes.length && bytes[i + 1] == '\n') continue;
      out.write(bytes[i]);
    }
    return out.toByteArray();
  }

  private void retire(UUID job, int generation) throws Exception {
    ledger.locked(
        () -> {
          ledger.fence(job, generation);
          runtime.removeAttempt(job, generation);
          ledger.release(job, generation);
          return null;
        });
    try {
      api.post("/internal/jobs/" + job + "/" + generation + "/cleaned", Map.of());
    } catch (ControlClient.Rejected e) {
      if (e.status != 409) throw e;
    }
  }

  private void reconcile() throws Exception {
    for (var a : ledger.active())
      if (System.currentTimeMillis() >= a.path("deadline").asLong())
        try {
          retire(UUID.fromString(a.path("job").asText()), a.path("generation").asInt());
        } catch (Exception e) {
          log("expired_cleanup_error", e);
        }
    var candidates = api.get("/internal/cleanup");
    for (var a : candidates)
      retire(UUID.fromString(a.path("job_id").asText()), a.path("generation").asInt());
    for (var a : ledger.active())
      if (System.currentTimeMillis() >= a.path("deadline").asLong())
        retire(UUID.fromString(a.path("job").asText()), a.path("generation").asInt());
    api.post(
        "/internal/node-heartbeat",
        Map.of(
            "load",
            Math.max(0, ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage())));
  }

  private static void log(String event, Exception e) {
    System.err.println(
        "{\"event\":\"" + event + "\",\"error_type\":\"" + e.getClass().getSimpleName() + "\"}");
  }
}
