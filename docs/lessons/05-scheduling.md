# 5. Hardware-aware scheduling

The scheduler first considers a bounded set of oldest ready jobs. It filters workers by supported language, registered image, heartbeat freshness and whether the worker is already occupied. It also checks the shared node's CPU, memory and slot budget and its quarantine state.

Only eligible candidates are ranked. The score combines expected runtime with current node load and a small occupied-slot penalty. Lower scores win. Historical runtime is an exponentially weighted moving average: `new = 0.8 × old + 0.2 × observation`. A language-specific prior divided by the node's configured speed is used before history exists.

This is a heuristic, not an optimal scheduler. Historical samples include cold container startup and compilation. A long-running program can influence the language-level estimate, and there is no claim that the estimate knows what arbitrary source will do. Safety never depends on prediction accuracy.

After choosing a worker, `Coordinator.dispatch()` locks the node row and recomputes eligibility. Every unfinished physical reservation counts, including abandoned attempts awaiting cleanup. Each attempt currently reserves one CPU and 512 MiB. A new worker process on the same node does not create more physical capacity.

A transaction-scoped advisory lock prevents concurrent coordinator loops from redundantly competing over the same dispatch batch. This simplifies a small local installation but limits scheduler throughput. `SKIP LOCKED` prevents waiting for an already busy job row. A larger release could partition scheduling by node or queue class after measuring the bottleneck.

**Exercise:** inspect the score for two eligible nodes with different expected runtimes and loads. Make the faster node run out of memory and prove it is excluded despite its attractive score.

**Explain it:** “Eligibility is a correctness decision; ranking is a performance decision. A faster prediction can never override a hard resource budget.”

Read `DistributedPolicyTest` and the real database test with 100 concurrent dispatch calls. A synthetic score example is not a measured throughput improvement.
