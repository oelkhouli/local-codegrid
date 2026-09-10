package dev.codegrid.domain;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Small input-contract checks; passing these does not complete the exercise. */
class LifecycleInputTest {
  private final JobLifecycle lifecycle = new JobLifecycle();

  @Test
  void rejectsMissingState() {
    assertThrows(NullPointerException.class, () -> lifecycle.transition(null, JobEvent.ASSIGN));
  }

  @Test
  void rejectsMissingEvent() {
    assertThrows(NullPointerException.class, () -> lifecycle.transition(JobState.QUEUED, null));
  }
}
