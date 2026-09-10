package dev.codegrid.domain;

/** Runs without optional JDK modules, matching a minimal deployment runtime. */
public class RetryRuntimeProbe {
  public static void main(String[] args) {
    for (int i = 0; i < 50; i++) {
      long delay = RetryPolicy.delay(1).toMillis();
      if (delay < 0 || delay > 2000) throw new AssertionError("Retry delay out of bounds");
    }
  }
}
