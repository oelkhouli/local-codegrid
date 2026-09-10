package dev.codegrid.worker;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxTest {
  @TempDir Path directory;

  @Test
  void engineFailureDiagnosticsIdentifyCauseWithoutExposingOutput() throws Exception {
    var runtime = new RuntimeEngine("docker", "local");
    var failure = assertThrows(RuntimeEngine.RuntimeFailure.class,
        () -> runtime.command(List.of("sh", "-c",
            "printf 'Error: No such image: PRIVATE_IMAGE_MARKER' >&2; exit 125"), 3));
    var diagnostic = WorkerMain.diagnostic("attempt_interrupted", failure);
    assertEquals("image_missing", diagnostic.get("reason"));
    assertEquals(125, diagnostic.get("exit_code"));
    assertFalse(ControlClient.JSON.writeValueAsString(diagnostic).contains("PRIVATE_IMAGE_MARKER"));
    assertTrue(diagnostic.get("error_frames").toString().contains("RuntimeEngine.command"));
    var wrapped = new java.io.IOException("PRIVATE_TOKEN", new java.net.ConnectException("PRIVATE_URL"));
    var network = WorkerMain.diagnostic("attempt_interrupted", wrapped);
    assertEquals("ConnectException", network.get("cause_type"));
    assertFalse(ControlClient.JSON.writeValueAsString(network).contains("PRIVATE_"));
  }

  private RuntimeEngine.Outcome result(byte[] output) {
    return new RuntimeEngine.Outcome(0, false, false, false, output, new byte[0], null, 10);
  }

  @Test
  void comparatorPreservesTrailingWhitespace() {
    assertEquals(
        "WRONG_ANSWER",
        WorkerMain.verdict(result("42 \n".getBytes(StandardCharsets.UTF_8)), "42\n"));
  }

  @Test
  void comparatorNormalizesOnlyCrLf() {
    assertEquals(
        "ACCEPTED", WorkerMain.verdict(result("42\r\n".getBytes(StandardCharsets.UTF_8)), "42\n"));
    assertEquals(
        "WRONG_ANSWER",
        WorkerMain.verdict(result("42\r".getBytes(StandardCharsets.UTF_8)), "42\n"));
  }

  @Test
  void invalidUtf8DoesNotBecomeAnAcceptedReplacementCharacter() {
    assertEquals("WRONG_ANSWER", WorkerMain.verdict(result(new byte[] {(byte) 0xff}), "�"));
  }

  @Test
  void nonzeroExitCannotPassByPrintingTheExpectedAnswer() {
    var r =
        new RuntimeEngine.Outcome(
            21, false, false, false, "42\n".getBytes(), new byte[0], null, 10);
    assertEquals("RUNTIME_ERROR", WorkerMain.verdict(r, "42\n"));
  }

  @Test
  void outputLimitIsAnExecutionVerdict() {
    var r = new RuntimeEngine.Outcome(137, false, true, false, new byte[0], new byte[0], null, 10);
    assertEquals("OUTPUT_LIMIT", WorkerMain.verdict(r, ""));
  }

  @Test
  void policyRejectsCallerChosenImagesAndPaths() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SandboxPolicy.create("docker", "local", UUID.randomUUID(), 1, 0, 1, "alpine:latest"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SandboxPolicy.create(
                "sh", "local", UUID.randomUUID(), 1, 0, 1, "sha256:" + "a".repeat(64)));
  }

  @Test
  void actualExecutionTemplateIncludesAllRequiredRestrictions() {
    var args =
        SandboxPolicy.create(
            "docker", "local", UUID.randomUUID(), 1, 0, 1000, "sha256:" + "a".repeat(64));
    assertTrue(
        args.containsAll(
            List.of(
                "--network",
                "none",
                "--read-only",
                "--cap-drop",
                "ALL",
                "--pids-limit",
                "128",
                "--memory",
                "512m",
                "--memory-swap",
                "--cpus",
                "1.0",
                "--user",
                "65534:65534",
                "no-new-privileges:true",
                "--log-driver")));
    assertFalse(args.contains("--privileged"));
    assertFalse(args.contains("--volume"));
    assertTrue(args.contains("20s"));
  }

  @Test
  void fencingSurvivesReopeningTheLedger() throws Exception {
    UUID job = UUID.randomUUID();
    var ledger = new NodeLedger(directory);
    ledger.begin(job, 1, System.currentTimeMillis() + 30000, 1, 536870912, 1000);
    ledger.locked(
        () -> {
          ledger.fence(job, 1);
          ledger.release(job, 1);
          return null;
        });
    var reopened = new NodeLedger(directory);
    assertThrows(
        IllegalStateException.class,
        () -> reopened.begin(job, 1, System.currentTimeMillis() + 30000, 1, 536870912, 1000));
    reopened.begin(job, 2, System.currentTimeMillis() + 30000, 1, 536870912, 1000);
  }

  @Test
  void twoJavaProcessesCannotBothReserveTheLastSlot() throws Exception {
    String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String cp = System.getProperty("java.class.path");
    Process a =
        new ProcessBuilder(javaBinary, "-cp", cp, LedgerProbe.class.getName(), directory.toString())
            .redirectErrorStream(true)
            .start();
    Process b =
        new ProcessBuilder(javaBinary, "-cp", cp, LedgerProbe.class.getName(), directory.toString())
            .redirectErrorStream(true)
            .start();
    a.getOutputStream().write('\n');
    a.getOutputStream().flush();
    b.getOutputStream().write('\n');
    b.getOutputStream().flush();
    assertTrue(a.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
    assertTrue(b.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(Set.of(0, 2), Set.of(a.exitValue(), b.exitValue()));
    try (var paths = Files.list(directory.resolve("active"))) {
      assertEquals(1, paths.count());
    }
  }
}
