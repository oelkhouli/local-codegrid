# ADR 0001: job lifecycle

Status: proposed — complete this in your own words after the exercise.

## Context

TODO: Explain submission, job and attempt using one example of your own.

## Decisions

1. TODO: Which states are terminal, and what invariant does terminality protect?
2. TODO: Why can a compiler error still produce a FINISHED job?
3. TODO: Why should an invalid transition throw instead of silently doing nothing?
4. TODO: Which responsibilities are intentionally absent from this pure function?
5. TODO: How can an HTTP cancellation endpoint be idempotent while the domain rejects a new transition from CANCELLED?

## Failure reasoning

TODO: Describe worker A/generation 7, lease loss, worker B/generation 8, and A's late success. Explain what this Java function cannot check and which conditions a future atomic database write must verify.

TODO: Explain why two workers on the same computer must share one resource budget rather than each reserving the whole machine.

## Alternatives considered

TODO: Explain why this exercise uses a pure function rather than an in-memory shared mutable Job object or a state-machine library. State a real tradeoff, not just a preference.

## Evidence

TODO: Record the test command/result and describe at least three tests you added, including the mistake each would catch.

## Scope limitations

TODO: Explicitly distinguish the serialized cancellation/completion unit tests from a future real database concurrency test.
