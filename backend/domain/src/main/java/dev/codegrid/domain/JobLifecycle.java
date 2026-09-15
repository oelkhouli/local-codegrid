package dev.codegrid.domain;

import java.util.Objects;

/** Pure lifecycle policy. A database transaction must also check ownership and fencing. */
public final class JobLifecycle {
  public JobState transition(JobState current, JobEvent event) {
    Objects.requireNonNull(current, "current");
    Objects.requireNonNull(event, "event");
    if (current.isTerminal()) throw new IllegalStateException("Terminal decisions are immutable");
    if (event == JobEvent.CANCEL) return JobState.CANCELLED;
    if (event == JobEvent.DEADLINE_REACHED) return JobState.FINISHED;
    return switch (event) {
      case ASSIGN ->
          require(current == JobState.QUEUED || current == JobState.RETRY_WAIT, JobState.LEASED);
      case START -> require(current == JobState.LEASED, JobState.RUNNING);
      case COMPLETE -> require(current == JobState.RUNNING, JobState.FINISHED);
      case RETRYABLE_FAILURE ->
          require(current == JobState.LEASED || current == JobState.RUNNING, JobState.RETRY_WAIT);
      case RETRIES_EXHAUSTED ->
          require(current == JobState.LEASED || current == JobState.RUNNING, JobState.FINISHED);
      default -> throw new IllegalStateException("Illegal lifecycle event");
    };
  }

  private JobState require(boolean legal, JobState next) {
    if (!legal) throw new IllegalStateException("Illegal state/event combination");
    return next;
  }
}
