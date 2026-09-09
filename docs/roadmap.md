# Ten-week learning roadmap and completion criteria

The complete reference implementation is supplied. To learn it, work through each stage on a practice branch: explain the invariant first, hide the referenced implementation, implement a small version, then compare behavior and tests. Never disable sandbox enforcement to simplify an exercise.

| Week | Stage and concepts | Measurable completion criteria |
|---|---|---|
| 1 | MVP: system boundaries and explicit Java lifecycle | Explain terminal state vs verdict; all lifecycle tests pass; draw the browser/API/worker trust boundaries. |
| 2 | MVP: schema, migrations, auth and ownership | Empty database migrates on startup; login/logout work; another user cannot read, cancel, or stream a job. |
| 3 | MVP: one Python worker and restricted execution | One job reaches a terminal result; CPU/memory/PID/root/network enforcement probes pass; infinite loop and output flood stop. |
| 4 | MVP: React submission and durable live logs | Recruiter launches with one command; submit, refresh/reconnect, inspect result, cancel queued work; no lost/duplicated displayed event sequence. |
| 5 | Intermediate: idempotency, transactions and races | 50 identical concurrent requests create one job; changed payload with reused key returns 409; parallel schedulers respect node budgets. |
| 6 | Intermediate: leases, generations, retries and cleanup | Worker kill produces a new generation or explicit deadline result; stale completion returns 409; cleanup retains reservations until confirmed. |
| 7 | Intermediate: all four languages, multiple workers | Java/Python/C++/JavaScript produce the expected answer; scaling processes does not multiply physical capacity; duplicate delivery stays at generation one. |
| 8 | Final: hardware-aware scheduler and multiple local computers | Explain score and EWMA bias; show node eligibility tests; add a second existing Linux engine over SSH, with its own token/ledger/budget. |
| 9 | Final: metrics, security and load experiments | Real sandbox/fault/browser suites pass; publish 1,000-observation latency sample and rejection counts; compare one/two slots without changing workload. |
| 10 | Final: operating recovery and recruiter demo | Public CI runs, secrets stay out of git/artifacts, backup can restore into empty DB, five-minute demo and all 15 explanation prompts completed. |

A realistic student MVP is weeks 1–4 with Python only, one worker, one test case and the essential sandbox checks. The supplied final reference adds all four languages, bounded benchmark mode, crash recovery, scheduling, security tests, observability and lessons. Multi-host setup is optional for the demonstration if no second computer is available; same-host replicas still exercise distributed processes and shared-resource concurrency.

The completion criteria describe evidence to collect, not a claim that all deployment experiments have already been performed in the author's build environment. Track actual runs in [verification](verification.md).
