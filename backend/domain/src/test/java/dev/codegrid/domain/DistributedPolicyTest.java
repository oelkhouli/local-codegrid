package dev.codegrid.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DistributedPolicyTest {
  private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void leaseBoundaryDoesNotGrantAnExtraInstant() {
    assertFalse(
        Fence.accepts(
            JobState.RUNNING, 2, 2, "b", "b", now, now, now.plusSeconds(20), now.plusSeconds(30)));
  }

  @Test
  void staleGenerationCannotWinEvenIfStateStillLooksRunning() {
    assertFalse(
        Fence.accepts(
            JobState.RUNNING,
            2,
            1,
            "b",
            "a",
            now,
            now.plusSeconds(10),
            now.plusSeconds(20),
            now.plusSeconds(30)));
  }

  @Test
  void currentWorkerStillNeedsTheRightGeneration() {
    assertFalse(
        Fence.accepts(
            JobState.RUNNING,
            2,
            1,
            "b",
            "b",
            now,
            now.plusSeconds(10),
            now.plusSeconds(20),
            now.plusSeconds(30)));
  }

  @Test
  void deadlineCannotBeExtendedByRenewal() {
    assertFalse(
        Fence.accepts(
            JobState.RUNNING, 2, 2, "b", "b", now, now.plusSeconds(10), now, now.plusSeconds(30)));
  }

  @Test
  void currentAuthorityCanReport() {
    assertTrue(
        Fence.accepts(
            JobState.RUNNING,
            2,
            2,
            "b",
            "b",
            now,
            now.plusSeconds(10),
            now.plusSeconds(20),
            now.plusSeconds(30)));
  }

  @Test
  void terminalJobRejectsAStillFreshLease() {
    assertFalse(
        Fence.accepts(
            JobState.CANCELLED,
            2,
            2,
            "b",
            "b",
            now,
            now.plusSeconds(10),
            now.plusSeconds(20),
            now.plusSeconds(30)));
  }

  @Test
  void retryCountAndDeadlineBothBoundRecovery() {
    assertTrue(RetryPolicy.mayRetry(2, now, now.plusSeconds(1)));
    assertFalse(RetryPolicy.mayRetry(3, now, now.plusSeconds(1)));
    assertFalse(RetryPolicy.mayRetry(1, now, now));
  }

  @Test
  void backoffIsBoundedAndJittered() {
    var random = new Random(8);
    Set<Long> samples = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      long delay = RetryPolicy.delay(2, random).toMillis();
      assertTrue(delay >= 0 && delay <= 4000);
      samples.add(delay);
    }
    assertTrue(samples.size() > 500);
  }

  SchedulingPolicy.Candidate c(
      String id, long expected, int usedCpu, long usedMemory, int usedSlots, boolean fresh) {
    return new SchedulingPolicy.Candidate(
        id, "node", expected, 0, 2000, usedCpu, 1073741824, usedMemory, 2, usedSlots, fresh, true);
  }

  @Test
  void aFastPredictionCannotOverrideMemoryOrCpu() {
    assertEquals(
        "eligible",
        SchedulingPolicy.choose(
                List.of(
                    c("too-much-cpu", 1, 2000, 0, 0, true),
                    c("too-much-memory", 1, 0, 1073741824, 0, true),
                    c("eligible", 800, 0, 0, 0, true)))
            .orElseThrow()
            .worker());
  }

  @Test
  void anOfflineWorkerIsNeverChosen() {
    assertTrue(SchedulingPolicy.choose(List.of(c("offline", 1, 0, 0, 0, false))).isEmpty());
  }

  @Test
  void historicalRuntimeChoosesAnEligibleFasterWorker() {
    assertEquals(
        "fast",
        SchedulingPolicy.choose(
                List.of(c("slow", 9000, 0, 0, 0, true), c("fast", 1000, 0, 0, 0, true)))
            .orElseThrow()
            .worker());
  }

  @Test
  void aFullNodeHasNoExtraCapacityForAnotherReplica() {
    assertTrue(
        SchedulingPolicy.choose(List.of(c("new-worker", 1, 2000, 1073741824, 2, true))).isEmpty());
  }
}
