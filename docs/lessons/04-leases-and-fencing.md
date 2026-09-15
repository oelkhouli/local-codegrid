# 4. Leases and fencing

A lease gives a worker permission to report for a limited time. Assignment initially grants up to 15 seconds. The worker renews regularly, and renewals never extend beyond the attempt deadline. The attempt's generation increases on each assignment.

Suppose A owns generation 1. Its lease expires and B later owns generation 2. A's late result must not overwrite B. `WorkerService.locked()` verifies the authenticated node and worker, then locks the job row. `Fence.accepts()` checks state, generation, worker identity, lease expiration, attempt deadline and overall job deadline. These checks and the following write share a transaction.

Equality at an expiry boundary is rejected: permission must still be valid, not exactly expired. PostgreSQL's clock determines database authority, so competing API instances do not use their own clocks for this decision.

The same guard protects renewals and new log writes. Protecting only final results would still let an old worker pollute the visible log or extend an expired lease. A byte-identical previously committed completion may receive an idempotent acknowledgement; that acknowledgement does not make a new authoritative write.

A lease is not a kill switch. An isolated process may continue after losing authority. Physical restrictions, independent timeouts and the node janitor address that separate problem.

**Exercise:** read `expiryKeepsPhysicalReservationAndFencesLateResults`. Explain why it rejects a renewal before assigning B, and why the old node's reservation remains charged.

**Explain it:** “Generation protects against an old owner; expiration protects against an owner whose permission has already ended, even before a replacement exists.”

**Boundary:** the design assumes ordinary clock discipline at the database. Local process lifetimes also use relative timeouts and a monotonic elapsed-time budget; a clock adjustment must not turn a single case into an unlimited execution.
