# Lesson 1: a job is a small state machine

By the end of this exercise you should be able to answer: "Can this event legally change this job's state?" You are not implementing the scheduler or proving distributed safety yet.

## Three objects, three purposes

A submission is the saved source and tests. A job is a request to evaluate that submission. An attempt is one worker's try at completing that job. If a worker fails, the job can survive through a new attempt. A deliberate rerun is a new job, even if its source is unchanged.

This separation lets us preserve the user's request while recording failed efforts honestly. A new result must not erase the fact that an earlier worker crashed.

## State versus event versus verdict

- **State:** where the job currently is, for example `QUEUED`.
- **Event:** something the system is trying to record, for example `ASSIGN`.
- **Verdict:** what happened to the user's program, for example `COMPILE_ERROR`.

The lifecycle function handles state plus event. It does not inspect the source code or carry the verdict. `COMPLETE` can finish an accepted program or a compiler-error result. `FINISHED` means the platform reached a final decision, not that the code passed. We will add the verdict/result record in a later slice.

An enum is Java's way to define a fixed set of named values. Here it prevents accidental spelling variants such as `RUNING` from becoming a job state.

## The function you will write

```java
JobState transition(JobState current, JobEvent event)
```

It is a pure function: the same inputs give the same result, and it does not change a database, call another service, or mutate a shared job. This makes the rule easy to test.

For example, assigning a queued job should produce `LEASED`. Trying to start a queued job should be rejected: that job has not been assigned yet. Reject an illegal transition by throwing `IllegalStateException`; silently returning the original state would disguise a caller's mistake.

Null arguments are programmer errors and are already rejected with `NullPointerException`. The existing `Objects.requireNonNull` calls provide those checks. Keep them.

## Contract to implement

This table is a behavioral specification, not an implementation. Choose an ordinary `if`/`switch` structure you can explain. Do not add a state-machine dependency.

| Current state | Event | Next state |
| --- | --- | --- |
| `QUEUED` | `ASSIGN` | `LEASED` |
| `RETRY_WAIT` | `ASSIGN` | `LEASED` |
| `LEASED` | `START` | `RUNNING` |
| `RUNNING` | `COMPLETE` | `FINISHED` |
| `LEASED` or `RUNNING` | `RETRYABLE_FAILURE` | `RETRY_WAIT` |
| `LEASED` or `RUNNING` | `RETRIES_EXHAUSTED` | `FINISHED` |
| Any nonterminal state | `CANCEL` | `CANCELLED` |
| Any nonterminal state | `DEADLINE_REACHED` | `FINISHED` |
| `FINISHED` or `CANCELLED` | Any event | Reject |
| Any other combination | — | Reject |

Only `FINISHED` and `CANCELLED` are terminal. `RETRY_WAIT` means the job can make progress later.

The future coordinator must decide whether a retry is due, whether attempts remain, whether a deadline really expired, and whether the reporting worker is authorized **before** supplying the corresponding event. That policy does not exist in this exercise. The event names are not substitutes for those future checks.

Repeated cancellation at the HTTP layer will be idempotent by returning the already-cancelled result without requesting a new transition. That is different from allowing a terminal job to transition again. The same distinction will matter for repeated completion messages.

## Read a test before writing the method

`anAssignedJobCanRunAndFinish` follows one user's successful lifecycle. The assertions inspect visible results. `executionCannotStartBeforeAssignment` inspects an invalid transition. Neither knows whether your implementation uses a switch, a map or several conditions.

`assertEquals(expected, actual)` checks a returned result. `assertThrows(IllegalStateException.class, () -> ...)` checks that an invalid action is rejected with the specified error type. The short `() -> ...` expression supplies an action for JUnit to run; it does not execute until the assertion calls it.

`JobStateTest` uses parameterized tests. JUnit runs the same test method once for each listed enum value. It is equivalent to repeating that check for each state, with clearer per-state failures.

The initial full suite has 16 test cases: two null-input checks and 14 exercise checks. The two input checks should pass; the others should fail at the intentionally unimplemented methods. Do not delete or disable them to make the output green.

## Your order of work

1. Run `./codegrid check` and confirm the input checks pass.
2. Implement `JobState.isTerminal()` and run `./codegrid test -Dtest=JobStateTest`.
3. Implement only assignment/start/completion first. Run `./codegrid test '-Dtest=JobLifecycleTest#anAssignedJobCanRunAndFinish'`.
4. Extend your method to retries, cancellation, deadlines and rejection rules. Run the entire suite.
5. Write at least three new tests in your own test class, using names that describe behavior.

Good extra tests include: a cancelled job cannot be reassigned, every nonterminal state respects a deadline, a leased-but-not-started worker failure can retry, and a queued job cannot claim completed execution. Pick at least one terminal-state rejection and one rule the supplied tests do not cover. Do not copy the production transition table into the test implementation or inspect private implementation details.

## Where this stops being enough

Suppose worker A owns attempt generation 7. Its lease expires. Worker B is assigned generation 8. A then reports success.

If we check only that the job is `RUNNING`, A's report could appear valid. This is why a correct state machine is necessary but not sufficient: later we must atomically compare worker identity, generation, lease deadline and job state in PostgreSQL. A Java `synchronized` method on one API process does not coordinate another API process.

Likewise, the cancellation/completion tests demonstrate the two possible serialized orders, not a real concurrent database race. Later integration tests must prove that the database permits only one authoritative terminal decision.

A lease is permission to report on a job for a limited time. It is not a physical kill switch. A process can keep running after its permission expires; the independent sandbox deadline and cleanup logic must handle that later.

## Explain before we move on

Complete the ADR with your own explanations, then send your methods and tests for review. We will inspect the code and your reasoning before introducing the database or execution engine.
