# ADR 0002: PostgreSQL authority and a shared node cleanup ledger

Status: accepted for the local portfolio implementation.

The first milestone described a learner-owned domain core. The user subsequently requested the completed reference implementation with lessons afterward. The lifecycle implementation and tests remain explicit Java; no workflow engine hides scheduling, retry, fencing, or isolation policy.

PostgreSQL owns jobs, reservations, leases, attempts and durable events. Redis lists carry disposable assignment hints, with a transactional database outbox, instead of becoming a second durable authority. A lost/duplicate hint is harmless because the API rechecks assignment ownership and generation.

Trusted workers directly control the engine, and an independent trusted reaper shares a persistent file-lock ledger. This replaces the earlier proposed narrow supervisor RPC with fewer deployable services. The cost is explicit: workers hold a powerful engine socket and are part of the trusted computing base. They must never execute source as control-plane code or accept arbitrary runtime flags, image references or host mounts from users.

Resource reservations survive expired leases until cleanup confirms all attempt containers are gone. Node IDs bind to a ledger UUID. This favors temporary reduced availability over silently exceeding host budgets during failure recovery. Further hardening could add a separately audited minimal supervisor or microVMs, but neither is claimed in this release.
