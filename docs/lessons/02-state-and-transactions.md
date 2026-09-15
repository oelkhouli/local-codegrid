# 2. State and transactions

`JobState` tracks scheduling progress: QUEUED, LEASED, RUNNING, RETRY_WAIT, FINISHED and CANCELLED. A verdict describes the program's outcome. FINISHED can mean ACCEPTED, COMPILE_ERROR or another final outcome. A compile error is completed work, not a reason to retry infrastructure.

`isTerminal()` compares the enum instance with FINISHED and CANCELLED. `JobLifecycle.transition()` rejects terminal states first, handles cancellation and deadlines, then checks the remaining legal state/event combinations. This function has no database, clock or shared mutable object.

A pure function cannot prevent two API processes from making conflicting decisions. `SELECT ... FOR UPDATE` makes competing database transactions serialize on a job row. The second transaction sees the first transaction's committed decision and must respect it. Java's `synchronized` only coordinates threads sharing that JVM; it cannot protect another API replica.

Spring's `@Transactional` wraps public service calls in database transactions. Calls from one method to another method on the same object do not pass through that proxy. The coordinator therefore uses `TransactionTemplate` explicitly for its background operations.

Read the cancellation method and the completion guard. Cancellation and completion may race. Either can win, but the job must have one authoritative terminal decision. The HTTP cancellation endpoint returns an already terminal state on a repeated request; it does not ask the pure lifecycle function to transition a terminal state again.

**Exercise:** run `cancellationAndCompletionHaveOneTerminalWinner` and explain both legal interleavings. Then add a test for a deadline while the job is still queued.

**Explain it:** “The state machine expresses allowed transitions. Row locks and transactions make those decisions atomic across processes.”

Do not claim that a sequence of unit-test calls proves a concurrent race. The integration test uses a real PostgreSQL server and competing threads.
