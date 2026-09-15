# Benchmarks and completion targets

The benchmark is an experiment on the stated host, not a promised universal score. Run the stack, stop unrelated heavy workloads, and save the exact commit, engine/OS, CPU count, RAM allocation, node budgets, worker count and image digests with each result. The script records host/platform and node metadata; add physical CPU model and engine version to the accompanying notes.

```sh
python3 scripts/benchmark.py --jobs 1000 --rate 2
```

It writes a JSON artifact under `artifacts/` containing offered, admitted, rejected, failed and completed work. Do not remove 429s, timeouts or wrong verdicts to improve a chart. It uses separate authenticated clients and an offered-rate schedule. Each offered submission has its own idempotency key; the load generator records admission rejection without silently retrying it. The bounded client thread pool is a limitation at high offered rates; keep measured client dispatch lag small and reduce the rate if the client saturates.

| Measurement | Definition |
|---|---|
| Offered load | Scheduled requests per second, including rejected work |
| Accepted throughput | ACCEPTED jobs divided by elapsed benchmark seconds |
| p50/p95/p99 end-to-end | Nearest-rank quantiles of admitted terminal jobs from submit to observed terminal state |
| Server latency | Database creation timestamp to terminal timestamp; includes queue and retries |
| Admission latency/rejections | Request round-trip and non-202/transport outcomes, kept separately |
| Failure rate | Non-ACCEPTED terminal jobs and transport failures reported separately from 429 admission rejection |
| Resource use | Configured CPU/memory/slot reservations plus worker JVM, host load and sampled sandbox memory |
| Cold sandbox duration | Container start through compile and run; fresh image filesystem each case/repetition |

There must be **at least 1,000 completed observations for a reported p99**, and preferably repeated runs with 10,000 observations across several bounded batches. Smaller CI samples are smoke measurements, not tail-latency evidence. Sampled sandbox memory can miss a short-lived peak and is nullable when no sample was available; it is never labeled exact peak RSS.

## Acceptance targets to measure

On an existing 4-core/8-GiB Linux machine with a two-slot node budget, use the provided one-case Python workload. These are targets to evaluate, not results already achieved:

- At low offered load (0.1 jobs/s), 100% correct results, no infrastructure failures, p95 end-to-end below 8s and p99 below 15s over at least 1,000 jobs.
- At increasing load (0.25, 0.5, 1, then 2 jobs/s), publish throughput and all three percentiles together with rejection/failure counts. Stop increasing load when queue delay or admission caps dominate.
- At identical hardware budgets, increasing worker count must never exceed reserved slots, CPU, or memory. More worker processes alone need not increase throughput when both slots are occupied.
- With one then two slots on hardware that has headroom, target at least 1.4x accepted throughput for the same saturated workload. A lower result is a finding to investigate, not permission to adjust the sample.
- In 20 worker-crash trials, every job either recovers within its deadline or reaches an explicit terminal verdict; zero stale completions and zero leaked reservations after cleanup. Duplicate notification delivery must not add an attempt.
- Submitted infinite loops stop; output stops at the attempt cap; memory/process/filesystem/network test fixtures reach the expected bounded outcomes. No reservation is released before container absence is established.

Grafana includes queue depth, completions/sec, failure ratio, duration quantiles, active workers, reserved resources, outbox backlog and quarantine. API request latency is not the same measurement as total job latency. See [verification](verification.md) for currently available evidence; no benchmark numbers are fabricated or copied from another host.
