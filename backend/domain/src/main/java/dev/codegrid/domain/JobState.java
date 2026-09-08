package dev.codegrid.domain;

/** Scheduling state, not whether a user's code was correct. */
public enum JobState {
    QUEUED,
    LEASED,
    RUNNING,
    RETRY_WAIT,
    FINISHED,
    CANCELLED;

    /** A terminal job cannot be transitioned again. */
    public boolean isTerminal() {
        // TODO 1: Return true exactly for terminal states.
        // Explain why RETRY_WAIT is not terminal in your decision record.
        throw new UnsupportedOperationException("TODO 1: implement JobState.isTerminal()");
    }
}
