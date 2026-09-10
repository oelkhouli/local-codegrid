# Five-minute demonstration

Before the call, run `./codegrid up`, read `./codegrid credentials`, and log in at `http://127.0.0.1:8080`. Keep Grafana open in another tab. First builds download large images: do this before the interview.

1. **0:00–0:45 — Show the product.** Submit the default Python example. Describe the path from session/ownership checks through a transactional job to a disposable sandbox. Point out the event timeline and compare expected/actual output.
2. **0:45–1:30 — Show compilation and error handling.** Switch to Java or C++, run the example, then introduce a syntax error. Explain why a compiler error is terminal and an infrastructure error may retry.
3. **1:30–2:15 — Show replay and benchmarking.** Run Python benchmark mode, refresh the selected job, and show replayed output and three result repetitions. These are fresh compile/run measurements, not warmed runtime-only benchmarks.
4. **2:15–3:15 — Show resource accounting.** Open Workers and Grafana. Run `./codegrid scale 3`; explain why three worker processes still share the two-slot budget. Show queue/latency/resource panels using actual recorded work.
5. **3:15–4:15 — Show failure recovery.** Use the prepared `python3 scripts/system_test.py --faults` result, or run a job with a short sleep and kill the worker processes using your engine's Compose command. Explain lease expiry, generation fencing, quarantine, cleanup and retry. Do not claim the generation counter prevents duplicate execution.
6. **4:15–5:00 — Explain one hard decision.** Open lesson 8 and the cleanup algorithm. Explain why physical cleanup must precede releasing capacity, then state the container/shared-kernel limitation and identify one improvement you would make next.

Useful questions to practice: Which transaction prevents double reservation? Why does Redis loss not lose a job? What happens if a response is lost after completion commits? Why is expected output excluded from the sandbox payload? What does your p99 sample size actually support?

Be accurate about AI assistance and what you personally implemented, tested, or changed. The strongest demonstration is editing a policy and explaining how its test and measured behavior change.
