# 6. Retries and the outbox

Infrastructure failures can be retried. Compiler errors, wrong answers, program errors and resource-limit verdicts are final outcomes. The implementation allows at most three attempts and also imposes an overall job deadline.

`RetryPolicy.delay()` uses full jitter: pick a random delay between zero and an exponentially increasing cap. The chosen `ready_at` is persisted. The worker slot is not occupied by a sleeping retry timer. Randomizing retries helps avoid a synchronized burst after several workers fail together.

A database transaction cannot atomically commit both a PostgreSQL change and a Redis notification. Assignment therefore inserts an outbox record in the same transaction as the lease. A separate publisher sends the notification and marks the outbox row published afterward.

If the publisher crashes after sending but before marking, the next publisher sends it again. That duplicate is expected. A worker's authoritative assignment still comes from PostgreSQL; pulling or duplicating a Redis item does not create another attempt.

If Redis loses a notification after the outbox row was marked, API assignment polling still finds the lease. Redis is an acceleration mechanism, not a required durable copy of the job. Submission admission intentionally fails closed when its Redis rate limiter is unavailable; already accepted work has a separate recovery path.

Read `Coordinator.publish()`, `WorkerService.assignment()` and `duplicateNotificationsDoNotCreateNewAttempts`.

**Exercise:** run `python scripts/system_test.py --faults`. Locate the repeated notification, then verify that the job still has one attempt in the duplicate-delivery scenario. Separately explain why worker-crash recovery can create a second attempt.

**Explain it:** “At-least-once delivery plus idempotent operations is an explicit recovery strategy. It is not exactly-once execution.”

A successful result lost in transit may cause physical recomputation if it was never committed. The project guarantees fenced authority, not that a program's instructions execute only once.
