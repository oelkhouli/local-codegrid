# 15. Explain and defend the project

Start with a one-minute description: “Local CodeGrid accepts Java, Python, C++ and JavaScript, schedules bounded execution on local workers, and streams persisted results. PostgreSQL controls ownership and retries. Redis supports admission and notifications. Containers enforce execution limits, and an independent cleanup process handles worker loss.”

Then demonstrate one normal run and one failure. Show a successful program, its bounded output and test result. Kill a worker with the documented operator script, then show the lost attempt, later generation and confirmed cleanup. Open Grafana and identify a measured value, a histogram percentile and a configured reservation.

Be ready to answer these questions without reading:

1. Why is a job different from an attempt?
2. Why are Redis notifications insufficient to establish ownership?
3. Which database lock prevents resource overcommit across workers?
4. What rejects a late result before a replacement worker even exists?
5. Why is a lease expiration insufficient to release CPU/memory reservations?
6. What prevents a late container start after the janitor cleaned an attempt?
7. How do you handle duplicate HTTP requests, log chunks and completion reports?
8. Why can two workers on one CPU budget fail to improve throughput?
9. What does the benchmark actually include?
10. What can a runtime-socket compromise do, and what does container isolation not guarantee?

For each answer, name the relevant code, state the invariant and give a failure example. Explain a tradeoff: serialized scheduling is easy to reason about but limits dispatch throughput; durable logs cost database writes; conservative cleanup can reduce utilization; a local host is inexpensive but remains a failure domain.

**Practice plan:** spend one session per lesson. First predict behavior, then read the code, run its test and explain the result aloud. Afterward modify a small behavior on a branch, add a meaningful test and defend the change.

Be candid about assistance. “I used an AI coding assistant to help implement the platform, then reviewed the design, reproduced the failure cases and extended the code” is defensible if that is what you did. Do not claim unperformed tests, invented benchmark numbers or sole authorship. The portfolio becomes yours through understanding and demonstrated engineering judgment.

**Final exercise:** record a five-minute walkthrough with no script. Ask someone to interrupt with a failure scenario. Trace what happens to both the database decision and the physical process.
