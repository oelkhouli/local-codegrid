# 13. Tests and failure injection

Unit tests exercise deterministic rules without infrastructure: terminal states, transition legality, retry bounds, candidate eligibility, fencing boundaries and output comparison. The ledger test launches two Java processes competing for one local slot; one process must lose.

Integration tests use actual PostgreSQL and Redis containers. They test 50 identical concurrent submissions, 100 competing dispatch calls, duplicate notifications, expired leases, stale results, log ordering, bounded retries and the cancellation/completion race. Database constraints and transactions cannot be validated by replacing the database with a Java map.

System tests use the real HTTP interface and runtime containers. All four language examples must pass. The security fixtures attempt network access, root writes, excessive memory/output/processes, a full workspace and an infinite loop. These programs must run only after the worker's enforcement probe succeeds.

The crash simulation kills worker processes while leaving the janitor and coordinator alive. Recovery must produce a later authorized attempt and retain the old attempt in history. The duplicate-notification simulation must not create an extra attempt merely because a notification repeats.

Playwright tests cover login, execution, history replay, mobile layout, lesson access and HTML-like program output. A screenshot is useful visual evidence but does not prove database or kernel isolation.

CI runs on a standard public-repository Linux runner, with scoped read permissions and pinned action revisions. Local scripts provide equivalent checks. A skipped integration suite is not a pass; the explicit integration profile requires a functioning container runtime.

**Exercise:** choose one invariant, introduce a small defect on a temporary branch, and show which test detects it. Restore the implementation and explain why that test checks observable behavior rather than copying the algorithm.

**Explain it:** “I test failure behavior at the boundary that enforces it: Java for pure rules, PostgreSQL for concurrency and the real runtime for isolation.”

A real deployment defect showed why this matters: the full JDK passed retry tests, but the slim JRE lacked the implementation selected by `RandomGenerator.getDefault()`. After a worker crash, every recovery cycle threw an exception. `MinimalRuntimeRetryTest` runs a separate JVM restricted to `java.base` and reproduces that environment difference. The production jitter source now uses `ThreadLocalRandom`; random draws are not security tokens, and the exponential-backoff algorithm remains explicit.
