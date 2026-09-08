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
        return this == FINISHED || this == CANCELLED;
    }
}
