package dev.codegrid.worker;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiConsumer;

public class RuntimeEngine {
  final String engine, node;

  static final class RuntimeFailure extends IOException {
    final String operation, reason;
    final int exitCode;

    RuntimeFailure(String operation, String reason, int exitCode) {
      super("Container engine operation failed");
      this.operation = Set.of("info", "image", "create", "ps", "inspect", "rm", "stats")
          .contains(operation) ? operation : "unknown";
      this.reason = reason;
      this.exitCode = exitCode;
    }
  }

  // Classify engine output locally; never emit raw output, which may contain private metadata.
  static String failureReason(String output) {
    String value = output.toLowerCase(Locale.ROOT);
    if (value.contains("no such image")) return "image_missing";
    if (value.contains("permission denied") || value.contains("operation not permitted"))
      return "permission_denied";
    if (value.contains("no space left on device")) return "disk_full";
    if (value.contains("already in use")) return "name_conflict";
    if (value.contains("cannot connect") || value.contains("connection refused"))
      return "engine_unreachable";
    if (value.contains("client version") && value.contains("too old")) return "engine_api_version";
    return "engine_command_failed";
  }

  public RuntimeEngine(String engine, String node) {
    if (!Set.of("docker", "podman").contains(engine))
      throw new IllegalArgumentException("Unsupported engine");
    this.engine = engine;
    this.node = node;
  }

  public record Outcome(
      int exit,
      boolean oom,
      boolean outputLimited,
      boolean timedOut,
      byte[] stdout,
      byte[] stderr,
      Long sampledMemoryBytes,
      long elapsedMs) {}

  public String command(List<String> command, int seconds) throws Exception {
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    var output = new ByteArrayOutputStream();
    AtomicBoolean overflow = new AtomicBoolean();
    Thread reader =
        new Thread(
            () -> {
              try (var in = p.getInputStream()) {
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) != -1) {
                  if (output.size() + n > 65536) {
                    overflow.set(true);
                    p.destroyForcibly();
                    break;
                  }
                  output.write(b, 0, n);
                }
              } catch (IOException ignored) {
              }
            });
    reader.setDaemon(true);
    reader.start();
    if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new RuntimeFailure(command.get(1), "command_timeout", -1);
    }
    reader.join(1000);
    if (overflow.get() || p.exitValue() != 0)
      throw new RuntimeFailure(command.get(1),
          overflow.get() ? "engine_output_limit" : failureReason(output.toString(StandardCharsets.UTF_8)),
          p.exitValue());
    return output.toString(StandardCharsets.UTF_8).trim();
  }

  public JsonNode info() throws Exception {
    return ControlClient.JSON.readTree(
        command(List.of(engine, "info", "--format", "{{json .}}"), 10));
  }

  public String image(String tag) throws Exception {
    String id = command(List.of(engine, "image", "inspect", "--format", "{{.Id}}", tag), 10);
    if (!id.startsWith("sha256:")) id = "sha256:" + id;
    if (!id.matches("sha256:[a-f0-9]{64}")) throw new IOException("Invalid immutable image ID");
    return id;
  }

  public void create(UUID job, int generation, int index, long deadline, String image)
      throws Exception {
    command(SandboxPolicy.create(engine, node, job, generation, index, deadline, image), 10);
  }

  /** Enforcement probes are trusted, short lived, and tagged for cleanup after a startup crash. */
  public void createProbe(UUID id, long deadline, String image) throws Exception {
    var args = new ArrayList<>(SandboxPolicy.create(engine, node, id, 1, 0, deadline, image));
    args.add(2, "--label=codegrid.probe=true");
    command(args, 10);
  }

  public void removeExpiredProbes() throws Exception {
    String ids =
        command(
            List.of(
                engine,
                "ps",
                "--all",
                "--quiet",
                "--filter",
                "label=codegrid.node=" + node,
                "--filter",
                "label=codegrid.probe=true"),
            8);
    for (String id : ids.split("\\s+")) {
      if (id.isBlank()) continue;
      if (!id.matches("[a-f0-9]{12,64}")) throw new IOException("Invalid probe container ID");
      try {
        long deadline =
            Long.parseLong(
                command(
                    List.of(
                        engine,
                        "inspect",
                        "--format",
                        "{{index .Config.Labels \"codegrid.deadline\"}}",
                        id),
                    3));
        if (deadline <= System.currentTimeMillis()) remove(id);
      } catch (IOException ignored) {
        // Normal worker cleanup can remove a probe between listing and inspection. Retry next tick.
      }
    }
  }

  public void remove(String name) throws Exception {
    try {
      command(List.of(engine, "rm", "--force", name), 8);
    } catch (Exception first) {
      String remaining =
          command(
              List.of(
                  engine,
                  "ps",
                  "--all",
                  "--quiet",
                  "--filter",
                  (name.matches("[a-f0-9]{12,64}") ? "id=" : "name=") + name),
              8);
      if (!remaining.isBlank()) throw first;
    }
  }

  public void removeAttempt(UUID job, int generation) throws Exception {
    String ids =
        command(
            List.of(
                engine,
                "ps",
                "--all",
                "--quiet",
                "--filter",
                "label=codegrid.node=" + node,
                "--filter",
                "label=codegrid.attempt=" + SandboxPolicy.key(job, generation)),
            10);
    for (String id : ids.split("\\s+"))
      if (!id.isBlank()) {
        if (!id.matches("[a-f0-9]{12,64}")) throw new IOException("Invalid container ID");
        remove(id);
      }
  }

  public Outcome run(
      String name,
      Object payload,
      long deadline,
      AtomicInteger budget,
      BiConsumer<String, String> logs)
      throws Exception {
    long started = System.nanoTime(),
        durationLimit =
            Math.max(0, Math.min(21000, deadline - System.currentTimeMillis())) * 1000000L;
    Process p = new ProcessBuilder(engine, "start", "--attach", "--interactive", name).start();
    AtomicBoolean capped = new AtomicBoolean();
    var stdout = new ByteArrayOutputStream();
    var stderr = new ByteArrayOutputStream();
    Thread out = reader(p.getInputStream(), stdout, "stdout", budget, capped, logs),
        err = reader(p.getErrorStream(), stderr, "stderr", budget, capped, logs);
    byte[] inputBytes = ControlClient.JSON.writeValueAsBytes(payload);
    Thread writer =
        new Thread(
            () -> {
              try (var input = p.getOutputStream()) {
                input.write(inputBytes);
              } catch (IOException ignored) {
              }
            });
    writer.setDaemon(true);
    writer.start();
    boolean timedOut = false;
    Long sampledMemory = null;
    long lastSample = System.nanoTime();
    try {
      while (!p.waitFor(100, TimeUnit.MILLISECONDS)) {
        if (capped.get()
            || System.currentTimeMillis() >= deadline
            || System.nanoTime() - started > durationLimit) {
          timedOut = !capped.get();
          remove(name);
          break;
        }
        if (System.nanoTime() - lastSample > 1_000_000_000L) {
          lastSample = System.nanoTime();
          try {
            var stats =
                ControlClient.JSON.readTree(
                    command(
                        List.of(engine, "stats", "--no-stream", "--format", "{{json .}}", name),
                        3));
            String percent = stats.path("MemPerc").asText("0%").replace("%", "");
            long memory = (long) (Double.parseDouble(percent) / 100 * SandboxPolicy.MEMORY);
            sampledMemory = sampledMemory == null ? memory : Math.max(sampledMemory, memory);
          } catch (Exception ignored) {
          }
        }
      }
      if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
      out.join(1000);
      err.join(1000);
      boolean oom = false;
      try {
        oom =
            Boolean.parseBoolean(
                command(List.of(engine, "inspect", "--format", "{{.State.OOMKilled}}", name), 3));
      } catch (Exception ignored) {
      }
      return new Outcome(
          p.isAlive() ? 137 : p.exitValue(),
          oom,
          capped.get(),
          timedOut,
          stdout.toByteArray(),
          stderr.toByteArray(),
          sampledMemory,
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    } finally {
      p.destroyForcibly();
    }
  }

  private Thread reader(
      InputStream stream,
      ByteArrayOutputStream retained,
      String type,
      AtomicInteger budget,
      AtomicBoolean capped,
      BiConsumer<String, String> logs) {
    Thread t =
        new Thread(
            () -> {
              try (stream) {
                byte[] b = new byte[1024];
                int n;
                while ((n = stream.read(b)) != -1) {
                  int before = budget.getAndAdd(n);
                  int keep = Math.max(0, Math.min(n, 32768 - before));
                  if (keep > 0) {
                    retained.write(b, 0, keep);
                    logs.accept(type, new String(b, 0, keep, StandardCharsets.UTF_8));
                  }
                  if (keep < n) {
                    capped.set(true);
                    break;
                  }
                }
              } catch (Exception ignored) {
              }
            });
    t.setDaemon(true);
    t.start();
    return t;
  }
}
