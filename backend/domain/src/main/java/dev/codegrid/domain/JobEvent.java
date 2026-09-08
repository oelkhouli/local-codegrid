package dev.codegrid.domain;

/**
 * Events already validated by the future coordinator's policy/authority checks.
 * This enum does not decide whether a retry is due or a lease is valid.
 */
public enum JobEvent {
    ASSIGN,
    START,
    COMPLETE,
    RETRYABLE_FAILURE,
    RETRIES_EXHAUSTED,
    CANCEL,
    DEADLINE_REACHED
}
