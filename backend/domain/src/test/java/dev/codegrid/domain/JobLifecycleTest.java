package dev.codegrid.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JobLifecycleTest {
    private final JobLifecycle lifecycle = new JobLifecycle();

    @Test
    void anAssignedJobCanRunAndFinish() {
        JobState state = lifecycle.transition(JobState.QUEUED, JobEvent.ASSIGN);
        assertEquals(JobState.LEASED, state);
        state = lifecycle.transition(state, JobEvent.START);
        assertEquals(JobState.RUNNING, state);
        assertEquals(JobState.FINISHED, lifecycle.transition(state, JobEvent.COMPLETE));
    }

    @Test
    void aLostWorkerCanBeRetriedAndTheJobCanFinish() {
        JobState state = lifecycle.transition(JobState.RUNNING, JobEvent.RETRYABLE_FAILURE);
        assertEquals(JobState.RETRY_WAIT, state);
        state = lifecycle.transition(state, JobEvent.ASSIGN);
        assertEquals(JobState.LEASED, state);
        state = lifecycle.transition(state, JobEvent.START);
        assertEquals(JobState.FINISHED, lifecycle.transition(state, JobEvent.COMPLETE));
    }

    @Test
    void exhaustedAttemptsFinishInsteadOfRetryingForever() {
        assertEquals(JobState.FINISHED,
                lifecycle.transition(JobState.RUNNING, JobEvent.RETRIES_EXHAUSTED));
    }

    @Test
    void aQueuedJobCanBeCancelled() {
        assertEquals(JobState.CANCELLED,
                lifecycle.transition(JobState.QUEUED, JobEvent.CANCEL));
    }

    @Test
    void completionCannotReplaceAnAlreadyCommittedCancellation() {
        JobState state = lifecycle.transition(JobState.RUNNING, JobEvent.CANCEL);
        assertThrows(IllegalStateException.class,
                () -> lifecycle.transition(state, JobEvent.COMPLETE));
    }

    @Test
    void cancellationCannotReplaceAnAlreadyCommittedCompletion() {
        JobState state = lifecycle.transition(JobState.RUNNING, JobEvent.COMPLETE);
        assertThrows(IllegalStateException.class,
                () -> lifecycle.transition(state, JobEvent.CANCEL));
    }

    @Test
    void executionCannotStartBeforeAssignment() {
        assertThrows(IllegalStateException.class,
                () -> lifecycle.transition(JobState.QUEUED, JobEvent.START));
    }

    @Test
    void aDeadlineCanFinishAJobThatNeverObtainedAWorker() {
        assertEquals(JobState.FINISHED,
                lifecycle.transition(JobState.QUEUED, JobEvent.DEADLINE_REACHED));
    }
}
