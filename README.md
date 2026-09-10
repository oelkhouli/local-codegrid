# Local CodeGrid

A self-hosted code execution lab: submit Java, Python, C++, or JavaScript, watch live output, compare test results, and investigate scheduling and failure recovery on your own hardware.

**Local runtime. No API keys, subscriptions, cloud accounts, or paid services.** The application, database, cache, compilers, dashboards, and lessons run locally. The first build downloads public open-source dependencies; subsequent execution needs no internet. GitHub Actions is optional development CI, using standard runners for this public repository.

## Launch

Install **Podman with a Compose provider** or **Docker Engine with Compose**, then start the engine. A Linux engine with cgroups v2, seccomp, at least **2 CPUs and 4 GiB RAM** is required. Use your existing Linux machine or the Linux VM included with Podman on Windows/macOS. Allow roughly 12–18 GB free disk for compiler images and build caches. Four GiB is the minimum configuration; builds and Java compilation may be slower on a busy machine.

After cloning, run this single command from the repository:

```sh
./codegrid up
```

In Windows PowerShell:

```powershell
.\codegrid.ps1 up
```

Open **http://127.0.0.1:8080**. Run `./codegrid credentials` (PowerShell: `.\codegrid.ps1 credentials`) for the generated `admin` password. Grafana is at **http://127.0.0.1:3000**, with the same initial administrator credentials. Keep the exact `127.0.0.1` origin; `localhost` is a different browser origin.

The launcher generates private local secrets, builds all four compiler images, starts the platform, and waits for sandbox enforcement probes. Startup fails if required limits are unavailable. See [operations](docs/operations.md) for engine setup, troubleshooting, additional local computers, and backups.

## What is implemented

- Spring Boot API, React/TypeScript interface, PostgreSQL migrations, Redis cache/rate limits/assignment notifications.
- Explicit Java state machine, transactional admission and scheduling, owner-scoped idempotency, generation fencing, renewable leases, bounded jittered retries, absolute deadlines, and an outbox.
- Hardware budgets shared across worker replicas, load/history scheduling, a shared file-lock ledger, and an independent cleanup process. Stale attempts retain reservations until cleanup is confirmed.
- One disposable sandbox per test and repetition: fixed image and commands, unprivileged UID, dropped capabilities, seccomp, no network or host mounts, read-only root, bounded CPU/memory/processes/tmpfs/files/output, and layered wall timeouts.
- Cookie sessions, BCrypt passwords, CSRF and WebSocket origin checks, ownership checks, rate limits, audit events, and node credentials.
- Ordered, durable WebSocket log replay; run history, result details, cold-run benchmarking, worker budgets, and 15 lessons in the interface.
- A PostgreSQL-backed challenge catalog with starter code for all four languages, visible examples, and server-controlled hidden tests. The starter set includes FizzBuzz, palindrome, factorial, maximum value, and pair sum; free-form execution remains available.
- Prometheus and provisioned Grafana panels; unit, PostgreSQL/Redis integration, browser, sandbox, fault-injection, and load-test suites.

**This is a bounded local portfolio lab, not a public hostile multi-tenant service.** Containers share a kernel; the worker and cleanup services hold a powerful engine socket. Read [SECURITY.md](SECURITY.md) before running other people's code. No isolation claim substitutes for keeping the host kernel and images patched.

## Try it

Select Python and run the included addition example with input `12 30` and expected output `42` followed by a newline. Switch languages, introduce a compiler error, and watch the event timeline. Benchmark mode performs **three fresh compile-and-run repetitions**, not a warmed microbenchmark.

```sh
./codegrid scale 3                         # more worker processes, same node budget
./codegrid test                            # Java unit tests, containerized build
./codegrid integration                     # real PostgreSQL + Redis via Testcontainers
python3 scripts/system_test.py --faults    # running stack: languages, limits, crash/replay
python3 scripts/benchmark.py --jobs 1000 --rate 2
./codegrid down                            # preserve data
```

Python 3 is needed only for operator test/benchmark scripts, not launch or application use. Browser tests use `npm ci`, `npx playwright install chromium`, and `npm run test:e2e` in `frontend/`. See [verification](docs/verification.md) for what has actually run and [benchmarks](docs/benchmarks.md) for measurement definitions and acceptance targets.

## Read and explain the project

| Document | Purpose |
|---|---|
| [Architecture](docs/architecture.md) | Services, state machine, tables, algorithms, and tradeoffs |
| [API contract](docs/openapi.yaml) | Public HTTP contract, authentication, limits, internal worker protocol |
| [Threat model](SECURITY.md) | Trust boundaries, controls, remaining risks |
| [Operations](docs/operations.md) | Start, scale, recover, back up, and add an existing computer |
| [Demo](docs/demo.md) | A five-minute recruiter walkthrough |
| [Lessons](docs/lessons/README.md) | Fifteen concepts with implementation references and exercises |
| [Roadmap and completion criteria](docs/roadmap.md) | A ten-week path through the finished implementation |
| [Verification](docs/verification.md) | Evidence and unverified deployment paths |

```text
backend/       Java domain algorithms, Spring API, execution worker, tests
frontend/      React interface, styles, Playwright browser tests
runners/       Fixed compiler images and the in-container phase driver
infra/         Image digests, database initialization, Prometheus/Grafana
scripts/       Real-stack security/fault tests and benchmark client
.github/       Public-repository CI workflow
docs/         Architecture, OpenAPI, operations, lessons, verification
```

The original [planning blueprint](docs/blueprint.md) is historical design context. The current implementation and architecture document define the shipped behavior. CodeGrid's original source is MIT licensed; bundled dependencies retain their own [licenses](THIRD_PARTY_NOTICES.md).
