# Verification record

This file separates executed evidence from implemented or planned checks.

## Latest complete run

[GitHub Actions run 34495953638](https://github.com/oelkhouli/local-codegrid/actions/runs/34495953638) passed on commit `d1c806d` using Java 21, Docker, PostgreSQL, Redis, and Chromium.

| Gate | Result |
|---|---|
| Java domain tests | 29 passed; zero failures or skips |
| Java worker tests | 9 passed; zero failures or skips |
| Real PostgreSQL/Redis integration | 12 passed; zero failures or skips |
| TypeScript production build | Passed |
| Compose build/start and host reachability | Passed |
| Languages, sandbox, crash, duplicate delivery | 17 checks passed |
| Playwright | 4 flows passed |
| Load smoke | 32/32 completed; zero execution failures |

The challenge integration case attempts to replace FizzBuzz's cases with a forged expected value. The owner-facing response contains one public example, the worker assignment contains all four catalog cases, and the forged case is absent. The browser flow solves Factorial and receives five accepted case results: one visible plus four server-controlled cases. The run also proves that Flyway applies `V2__challenge_catalog.sql` to a new deployment.

The system suite executes Java, Python, C++, and JavaScript and checks network denial, a read-only root, absence of host secrets, workspace quota, process and memory limits, output limits, wall timeout, compiler/runtime errors, wrong answers, worker SIGKILL recovery, and duplicate notification delivery.

## Earlier failures that improved the design

The first real-host run found that services attached only to Docker internal bridges had unreachable published loopback ports. A separate ingress bridge now attaches only to web and Grafana; startup verifies both from the host. Submitted-code containers still run with no network.

A later worker-kill run found retry transactions rolling back because `RandomGenerator.getDefault()` selected an implementation absent from the slim JRE. A separate minimal-runtime regression reproduces that environment with `--limit-modules java.base`; retry jitter now uses `ThreadLocalRandom`, which is available in java.base. The latest complete run passed worker-crash recovery.

The account-switch browser test holds an authenticated private job response across logout and another account's login. Session epochs prevent that response or the first account's unsaved draft from appearing in the new workspace.

These failures show why unit tests and healthy container status alone do not establish end-to-end correctness.

## Local build evidence

The implementation workspace packaged the API, domain, and worker modules and ran 38 unit tests using its available JDK 17 with `-Dmaven.compiler.release=17`. The project and passing CI target Java 21. Strict TypeScript and the production Vite build passed locally. The workspace has no Docker/Podman daemon, so real runtime evidence comes from the linked CI run and the user's successful Windows launch.

## Remaining host-specific experiments

- Windows launch succeeded on the user's machine; the full automated suite has not been repeated there.
- Podman/rootless Podman and the second-computer SSH worker profile remain unverified on those hosts.
- Backup restoration into an empty database with the recovered ledger remains to be demonstrated.
- At least 1,000 completed samples are required before reporting p99. Repeated load/slot comparisons and 20 crash trials remain larger experiments.

The passing Docker deployment does not prove protection against every kernel or container-engine exploit. This is a bounded local portfolio lab; see [SECURITY.md](../SECURITY.md) for its trust boundaries and [benchmarks](benchmarks.md) for measurement rules.
