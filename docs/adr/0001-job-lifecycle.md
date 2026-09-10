# ADR 0001: explicit job lifecycle

Status: accepted in the completed reference implementation.

A submission is immutable source plus test data. A job is a requested run of that submission. An attempt is one leased execution generation. The lifecycle policy is a pure function from current state and event to next state; database services separately enforce ownership, row locking and fencing.

`FINISHED` and `CANCELLED` are terminal. `FINISHED` means the platform has made its final decision, so a compiler error, wrong answer or timeout can legitimately finish a job. The verdict says what happened. Illegal transitions throw; silently accepting them would conceal concurrency and protocol defects.

Every active state can be cancelled or reach its overall deadline. Assignment applies only to QUEUED/RETRY_WAIT; start applies to LEASED; ordinary completion applies to RUNNING. Infrastructure failure can move LEASED/RUNNING to RETRY_WAIT until the three-attempt budget or absolute deadline is exhausted. See `JobLifecycle` for the complete transition table.

The HTTP cancellation endpoint can be idempotent while the domain rejects further transitions from CANCELLED: the service returns an existing terminal decision without requesting another transition. Cancellation and completion lock the same PostgreSQL job row, so only one terminal decision commits.

A worker with an old generation cannot renew, log or complete after a new generation is assigned. The pure lifecycle cannot determine that by itself: the service must check node/worker identity, current generation, active state, lease time and both deadlines in the transaction. There may still be duplicate physical execution; there is one accepted final decision.

Workers on one engine share the node resource budget and a persistent file-lock ledger. Expiring a lease does not prove its container stopped. Cleanup must fence late starts, confirm removal, then release reservations. This is deliberately separated from terminality.

A pure policy avoids shared in-memory state across API replicas and makes the rules readable without a workflow library. The tradeoff is that services must explicitly apply it and retain the transactional invariants. Unit tests cover legal/illegal transitions and immutable terminal states; `ControlPlaneIT` tests real database races, and the deployed crash test checks the whole recovery path. Actual execution evidence is recorded in `docs/verification.md`.
