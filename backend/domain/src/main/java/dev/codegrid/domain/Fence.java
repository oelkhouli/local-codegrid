package dev.codegrid.domain;

import java.time.Instant;

public final class Fence {
  public static boolean accepts(
      JobState state,
      int currentGeneration,
      int reportedGeneration,
      String currentWorker,
      String reportingWorker,
      Instant now,
      Instant leaseUntil,
      Instant attemptDeadline,
      Instant jobDeadline) {
    return (state == JobState.LEASED || state == JobState.RUNNING)
        && currentGeneration == reportedGeneration
        && currentWorker.equals(reportingWorker)
        && now.isBefore(leaseUntil)
        && now.isBefore(attemptDeadline)
        && now.isBefore(jobDeadline);
  }

  private Fence() {}
}
