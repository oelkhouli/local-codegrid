# Operations

## Prerequisites and start

Use an existing Linux computer with cgroups v2, seccomp, a current Podman or Docker Engine, and a Compose provider. Podman supplies an open-source route on Windows/macOS through its Linux VM. No purchased hardware is required. Allocate at least 2 vCPUs, 4 GiB memory and 12–18 GB free disk to the engine; low-memory builds can be slow. The startup budget is one sandbox slot on a 2-CPU engine, two on larger engines, each reserving one CPU and 512 MiB.

For rootless Podman on Linux, enable the local user socket before first use:

```sh
systemctl --user enable --now podman.socket
podman info
podman compose version
./codegrid up
```

On Podman for Windows/macOS, initialize/start its existing Linux machine using the normal Podman installation workflow, install a Compose provider if missing, and use `./codegrid up` or `.\codegrid.ps1 up`. The Compose socket mount must address the engine's Linux socket. If automatic detection is wrong for your installation, set `ENGINE_SOCKET` to that socket path. Rootless cgroup delegation must enforce CPU, memory, swap and PID limits; the worker refuses unsupported configurations.

Select Docker explicitly with `CODEGRID_ENGINE=docker ./codegrid up` when both engines are installed. On Linux, use Docker Engine plus Compose without requiring Docker Desktop. First startup downloads pinned image manifests and package dependencies; the database/control networks are private, and user programs cannot access a network. Only the web/Grafana listeners also attach to an ingress bridge so their explicit loopback port publications work.

Use `http://127.0.0.1:8080`, not a substituted hostname. If changing the web port, update both `WEB_PORT` and `PUBLIC_ORIGIN` in `.env` before recreating the API/web services. `./codegrid credentials` shows the initial admin password. Secrets are generated once with owner-only file permissions; do not commit or paste them into an issue.

## Health, resources and troubleshooting

`./codegrid status` and `./codegrid logs worker reaper api` show readiness and failures. A worker runs real restrictions probes in every language image before registering. These trusted Python probes use short-lived containers and control-plane headroom; the reaper removes expired probe containers left by a startup crash. A startup failure is actionable: check cgroups v2, seccomp, engine socket access, RAM/CPU allocation and image builds. Do not remove restrictions to bypass a probe failure. A cold start may take several minutes to build; the 120-second readiness wait begins after builds.

A quarantined node has a stale/uncleaned attempt. Keep the reaper and engine available; it fences the ledger and removes all matching containers before acknowledging cleanup. The database deliberately retains reservations until that acknowledgment. If PostgreSQL is down, local expired-intent cleanup still runs; the API acknowledgment retries after recovery. If Redis is down, new browser requests/admission fail closed on rate limits while existing authenticated worker operations continue through PostgreSQL.

`./codegrid scale N` runs 1–8 worker processes against the same node budget. It does not allocate more CPU or memory. A process handles one assignment at a time. Node budget is established at bootstrap and stored in PostgreSQL; changing `.env` later does not silently resize an existing node. Use a fresh explicitly reset demo or an audited operator migration to change a persistent node budget. Leave control-plane and host headroom.

API replicas can be launched with the engine's `compose up -d --scale api=2 api` command. Sessions, events, idempotency and scheduling locks are shared. Nginx resolves the API service on startup, so recreate the web service after API topology changes. Existing WebSockets reconnect and replay their durable cursor. PostgreSQL's 60-connection cap and per-API pool size bound how many replicas are sensible.

## Tests and evidence

```sh
./codegrid test
./codegrid integration
python3 scripts/system_test.py --faults
python3 scripts/benchmark.py --samples 1000 --rate 2
```

Unit tests need no runtime engine inside their logic; the launcher merely supplies Maven/JDK. Integration tests use Testcontainers with real PostgreSQL/Redis and therefore require engine access. Docker-in-Docker is not used: the tests profile mounts the trusted host engine socket and uses `host-gateway` to reach published test container ports. For a Podman installation without compatible Testcontainers networking, run Maven with Java 21 on the host against its supported Docker-compatible socket. This compatibility path must be tested on that installation; it is not a reason to skip tests and claim success.

Browser verification, from `frontend/` with Node 22.12+:

```sh
npm ci
npx playwright install chromium
npm run test:e2e
```

The public GitHub workflow uses ordinary `ubuntu-latest` runners, no larger paid runners, no secrets, no publishing, and no cloud runtime. CI writes test counts and the small benchmark smoke result to its job summary and logs. It does not upload artifacts or create dependency caches, so this workflow cannot consume paid artifact/cache storage. Browser reports remain available when running locally. Full p99 experiments belong on your documented local hardware. Never upload `.env` or databases. [GitHub billing reference](https://docs.github.com/en/billing/concepts/product-billing/github-actions).

## Backup, restore and stop

`./codegrid down` preserves volumes. `./codegrid backup` creates an owner-only PostgreSQL custom-format backup under `artifacts/`. Store it and `.env` securely. Do not include backups in public artifacts. Backups include durable jobs, source, session/token hashes, and idempotency mappings. The node ledger is also persistent state: never restore active jobs onto an unrelated ledger and assume the original execution has stopped.

For a normal recovery, stop admission/workers, allow running attempts to finish and clean, stop the stack, and keep the existing `.env` and `node-ledger` volume alongside the database backup. Restore only into an **empty** database on the same recovered installation:

```sh
# Start only PostgreSQL in a prepared installation with its empty database.
docker compose --env-file .env -f compose.yaml up -d postgres
./codegrid restore artifacts/codegrid-YYYYMMDDTHHMMSSZ.dump
./codegrid up
```

The placeholder filename is the actual file from `./codegrid backup`. Restore refuses a nonempty public schema, uses the non-superuser application role as owner, and never drops tables for you. Do not delete the old volume as a shortcut; preserve it until recovery is validated. Disaster recovery onto replacement storage requires first establishing that the old engine has stopped and reconciling node/ledger bindings under an operator-controlled migration.

`./codegrid reset --yes-delete-data` **permanently deletes project volumes**. Use only for a disposable demo after saving anything needed. The flag is required. The generated `.env` remains local for the next fresh bootstrap. Reset is not a fault-recovery algorithm.

## Add another existing Linux computer

This optional profile adds one worker plus one reaper on a second existing Linux engine. Linux host networking here is only for the trusted control processes to reach a loopback SSH tunnel; submitted-code containers still have `--network=none`. Do not scale this host-network worker profile because its local metrics port is fixed; separate computers each use their own node identity.

On the primary machine, register the new node and expose the internal API only to a local SSH endpoint:

```sh
python3 scripts/register_node.py laptop2 --cpu 1000 --memory 536870912 --slots 1
docker compose --env-file .env -f compose.yaml -f compose.lan.yaml up -d api
```

Use `podman compose` instead when selected. Transfer `.env.node` to the second machine using SSH/SCP, keeping mode 0600. Never reuse the primary node token/ID or copy its ledger. Clone the same commit on the second computer and build its worker and language images (the main `.env.example` is used only to satisfy interpolation of unrelated service definitions while building):

```sh
docker compose --env-file .env.example -f compose.yaml --profile build build worker runner-java runner-python runner-cpp runner-javascript
```

On that second machine, keep this SSH connection running in another terminal, replacing the host and user with your own SSH account:

```sh
ssh -NT -L 127.0.0.1:18081:127.0.0.1:8081 your-user@primary-machine
```

Then start the node:

```sh
docker compose --env-file .env.node -f compose.worker.yaml up -d
```

For rootless Podman, export `ENGINE_SOCKET` from `podman info --format '{{.Host.RemoteSocket.Path}}'` before running the equivalent command. The primary Workers page should show two independent node budgets. Fresh workers from both compete for eligible work. Killing the SSH tunnel removes authority; leases expire, the old node retains reservations, and jobs may retry on the other healthy node. The reaper reconciles when the tunnel returns.

Remote per-worker metrics stay on that machine's loopback (9102/9103). The central dashboard still sees database node budgets, throughput and latency; it does not automatically scrape those remote process metrics. Add deliberate SSH port forwards and Prometheus targets if you need them. Never publish an unauthenticated engine TCP socket or the internal API on a public/LAN wildcard address.
