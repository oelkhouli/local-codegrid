package dev.codegrid.domain;

import java.util.Objects;

/**
 * Pure state-transition rules. No clocks, database, mutation, or external calls.
 * Later, PostgreSQL transactions must enforce ownership and concurrency too.
 */
public final class JobLifecycle {

    /**
     * Returns the next state without changing any shared object.
     *
     * @throws NullPointerException if either argument is null
     * @throws IllegalStateException if the event is illegal in this state
     */
    public JobState transition(JobState current, JobEvent event) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(event, "event");

        // TODO 2: Implement the contract in docs/lessons/01-job-lifecycle.md.
        // Reject terminal states and disallowed event/state combinations.
        // Do not silently leave an invalid transition in the current state.
        throw new UnsupportedOperationException("TODO 2: implement JobLifecycle.transition()");
    }
}
