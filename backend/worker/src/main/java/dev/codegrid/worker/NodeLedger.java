package dev.codegrid.worker;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

/** Shared volume + OS file lock coordinate worker processes and the independent janitor. */
public class NodeLedger {
  private final Path root;

  public NodeLedger(Path root) throws IOException {
    this.root = root;
    Files.createDirectories(root.resolve("active"));
    Files.createDirectories(root.resolve("closed"));
  }

  public synchronized <T> T locked(Callable<T> operation) throws Exception {
    try (var channel =
            FileChannel.open(
                root.resolve("node.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        var lock = channel.lock()) {
      return operation.call();
    }
  }

  public UUID identity() throws Exception {
    return locked(
        () -> {
          Path p = root.resolve("identity");
          if (!Files.exists(p))
            Files.writeString(p, UUID.randomUUID().toString(), StandardOpenOption.CREATE_NEW);
          return UUID.fromString(Files.readString(p).trim());
        });
  }

  public void begin(UUID job, int generation, long deadline, int slots, long memory, int cpu)
      throws Exception {
    locked(
        () -> {
          String key = SandboxPolicy.key(job, generation);
          if (Files.exists(closed(key)) || Files.exists(active(key)))
            throw new IllegalStateException("Attempt already started or fenced");
          long count;
          try (var paths = Files.list(root.resolve("active"))) {
            count = paths.count();
          }
          if (count >= slots
              || (count + 1) * SandboxPolicy.MEMORY > memory
              || (count + 1) * 1000 > cpu)
            throw new IllegalStateException("Local node budget exhausted");
          Path temporary = Files.createTempFile(root, "intent-", ".tmp");
          Files.writeString(
              temporary,
              ControlClient.JSON.writeValueAsString(
                  Map.of("job", job.toString(), "generation", generation, "deadline", deadline)));
          Files.move(temporary, active(key), StandardCopyOption.ATOMIC_MOVE);
          return null;
        });
  }

  public void allowed(UUID job, int generation) throws IOException {
    String key = SandboxPolicy.key(job, generation);
    if (Files.exists(closed(key)) || !Files.exists(active(key)))
      throw new IllegalStateException("Local attempt is fenced");
    var record = ControlClient.JSON.readTree(Files.readString(active(key)));
    if (System.currentTimeMillis() >= record.path("deadline").asLong())
      throw new IllegalStateException("Local hard deadline expired");
  }

  /**
   * Must be under the same OS lock used by every container creation. Write fence before removing
   * runtime objects.
   */
  public void fence(UUID job, int generation) throws IOException {
    Files.writeString(
        closed(SandboxPolicy.key(job, generation)),
        Long.toString(System.currentTimeMillis()),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  public void release(UUID job, int generation) throws IOException {
    Files.deleteIfExists(active(SandboxPolicy.key(job, generation)));
  }

  public List<JsonNode> active() throws Exception {
    return locked(
        () -> {
          List<JsonNode> records = new ArrayList<>();
          try (var paths = Files.list(root.resolve("active"))) {
            for (Path p : paths.toList())
              records.add(ControlClient.JSON.readTree(Files.readString(p)));
          }
          return records;
        });
  }

  private Path active(String key) {
    return root.resolve("active").resolve(key + ".json");
  }

  private Path closed(String key) {
    return root.resolve("closed").resolve(key);
  }
}
