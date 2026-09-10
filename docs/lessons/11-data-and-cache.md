# 11. Schema, indexes and caching

Open `V1__core.sql`, then the additive `V2__challenge_catalog.sql` migration. Foreign keys connect users, submissions, problems, jobs, attempts and events. Unique keys enforce idempotency mappings and attempt generations. Check constraints encode limits such as allowed states, maximum source bytes and agreement between terminal state, verdict and completion timestamp.

The challenge catalog illustrates data projection as a security boundary. PostgreSQL holds visible and hidden cases, but `/api/problems` selects only statements, starters and visible examples. During admission the server discards browser-supplied cases for a challenge and stores the authoritative combined set. Worker assignments read that set; owner-facing job reads substitute the visible cases. Since this is open-source software running on the owner's machine, “hidden” means unavailable through the normal user API—not secret from the host administrator.

Indexes follow query patterns. `(owner_id, created_at)` supports recent history for one account. A partial index on ready jobs excludes completed history from scheduling scans. Attempt indexes find expired leases and outstanding reservations. `(job_id, seq)` makes event replay an ordered range query.

Indexes speed reads at the cost of disk space and additional work on writes. Adding an index to every column is not a design. Use PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)` with representative data to check the actual plan. Do not use a tiny empty database to claim production performance.

Redis caches immutable submission data for five minutes. Ownership is verified before a user receives that data. Current leases and mutable attempt cleanup state are read from PostgreSQL, avoiding a stale cache granting authority. Cache failures fall back to the database for this path.

Flyway records which migrations have run and their checksums. After a release has been used, change the schema with a new migration rather than editing an applied one. Version 1 defines the first release; a future change would be V2.

The local installation bounds history admission at 10,000 jobs and has per-job source/output limits. This is a coarse disk-growth policy, not a precise filesystem quota for the PostgreSQL volume. Operators must monitor available disk, preserve backups and choose a retention policy appropriate to their machine.

**Exercise:** explain the index used by event replay, inspect the query plan for history, and identify which cache entries can safely be stale.

**Explain it:** “The database constrains relationships and serializes authority. The cache saves repeat reads but cannot grant execution rights.”
