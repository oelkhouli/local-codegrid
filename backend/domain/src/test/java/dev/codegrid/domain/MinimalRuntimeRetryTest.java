package dev.codegrid.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MinimalRuntimeRetryTest {
  @Test
  void retriesWorkWithoutOptionalRandomProviderModules() throws Exception {
    String binary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    Process process =
        new ProcessBuilder(
                binary,
                "--limit-modules",
                "java.base",
                "-cp",
                System.getProperty("java.class.path"),
                RetryRuntimeProbe.class.getName())
            .redirectErrorStream(true)
            .start();
    try {
      assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Minimal runtime stalled");
      String diagnostic =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(0, process.exitValue(), diagnostic);
    } finally {
      process.destroyForcibly();
    }
  }
}
