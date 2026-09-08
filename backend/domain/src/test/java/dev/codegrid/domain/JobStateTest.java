package dev.codegrid.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobStateTest {
    @ParameterizedTest
    @EnumSource(value = JobState.class, names = {"FINISHED", "CANCELLED"})
    void finalDecisionsAreTerminal(JobState state) {
        assertTrue(state.isTerminal());
    }

    @ParameterizedTest
    @EnumSource(value = JobState.class,
            names = {"QUEUED", "LEASED", "RUNNING", "RETRY_WAIT"})
    void unfinishedWorkCanStillProgress(JobState state) {
        assertFalse(state.isTerminal());
    }
}
