# 3. Idempotency and admission

A client can lose an HTTP response after the server commits a job. Retrying with a new identifier would create another job. Retrying with the same `Idempotency-Key` lets the server recognize the original request.

`JobService.submit()` validates the request and computes a SHA-256 hash of its normalized data. It locks the user's database row, checks the `(owner_id, key)` record, and either returns the saved job ID or rejects a conflicting payload. A new submission, job, key mapping and event are committed together.

The unique database key is a final integrity constraint. The per-user lock also makes the active-job quota atomic. A short global advisory lock protects the installation's history-capacity check. These locks limit admission concurrency; they do not remain held during execution.

The React client preserves its key when a request fails without a confirmed response. Changing the draft produces a new key. A successful submission followed by another deliberate run also produces a new key. Identical source is not automatically the same user's intent.

Idempotency is scoped to one account. Otherwise two users submitting the same key could accidentally share an identifier or reveal another user's result. Records are retained with this bounded local history; there is no hidden short expiration that silently creates a second job.

Read `parallelIdenticalSubmissionsCreateOneJob`: 50 concurrent calls must produce one job. Read the changed-payload test too; deduplication must not silently accept different work under the same key.

**Exercise:** submit a job, save its key, and repeat the same request. Then change one expected-output byte while retaining the key. Predict the HTTP statuses and row counts before testing.

**Explain it:** “Idempotency controls repeated intent. Active-job quotas control outstanding work. Rate limits control request frequency. Each protects a different resource.”
