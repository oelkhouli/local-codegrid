# 12. Measurements that mean something

Throughput is completed work per unit time. Latency is the time one operation takes. A system can increase throughput while making individual requests wait longer, so report both.

CodeGrid records queue delay, end-to-end completion time, cold sandbox duration, retries, verdict counts, stale writes and reserved resources. JVM memory is measured separately. Container memory in a result is a sampled maximum; a short-lived container can finish before sampling. A reservation is a budget, not a measurement of actual use.

The benchmark mode repeats each test three times in fresh containers. Its elapsed duration includes startup, compilation and execution. It is deliberately labeled cold compile-and-run. Do not present it as an isolated microbenchmark of an algorithm or compare it directly with warmed JMH measurements.

The load script offers requests at a configured schedule and records every outcome, including admission rejections and transport failures. Its JSON includes raw samples, node budgets, offered rate, successful throughput and nearest-rank p50/p95/p99 completion latency. The server timestamps exclude client polling delay; client-observed duration is also retained.

A p99 from 30 observations is weak evidence. Use at least 1,000 completed samples per main configuration, repeat trials and record CPU/VM allocation and competing workloads. The report explicitly flags a small tail sample. Do not average worker p95 values: aggregate histogram bucket counts before calculating a percentile.

**Exercise:** run the same workload with one and two workers under the same node budget. Report rejections and queue growth alongside throughput. If capacity is unchanged, explain why more workers may not improve performance.

**Explain it:** “My benchmark names its measurement boundary and preserves raw observations. I distinguish measured results, configured limits and performance targets.”

Read `scripts/benchmark.py`, `ResourceMetrics`, and the Grafana queries. A CI smoke measurement validates the reporting path, not your laptop's performance.
