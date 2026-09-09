package dev.codegrid.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;

public final class RetryPolicy {
  public static final int MAX_ATTEMPTS = 3;

  public static boolean mayRetry(int attempts, Instant now, Instant deadline) {
    return attempts < MAX_ATTEMPTS && now.isBefore(deadline);
  }

  /** Full jitter, persisted by the caller; never sleep while holding a reservation. */
  public static Duration delay(int attempts, RandomGenerator random) {
    long cap = Math.min(30_000L, 2_000L << Math.min(Math.max(attempts - 1, 0), 4));
    return Duration.ofMillis(random.nextLong(cap + 1));
  }

  private RetryPolicy() {}
}
