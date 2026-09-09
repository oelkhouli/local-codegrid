# Verification record

This file separates implemented checks from executed evidence. Do not infer a passing runtime from the presence of a test or workflow.

## Executed in the implementation workspace

- Maven packaged all three Java modules and their executable API/worker jars. **37 unit tests passed**: 28 domain cases and 9 worker cases, including a race between two separate JVMs for one shared ledger slot.
- The available workspace JDK is **17**, so this local Maven run explicitly used `-Dmaven.compiler.release=17`. The project and container/CI targets are Java **21**. A Java 21 integration run is a separate gate.
- `npm ci` and `npm run build` completed with strict TypeScript checking and a production Vite build.
- All 12 public base-image manifest digests were resolved from their registries and recorded in `infra/images.lock.json`.

## Implemented, requiring a real container host

- `ControlPlaneIT`: 11 integration scenarios using actual PostgreSQL and Redis, including parallel idempotency, scheduler budget races, stale leases, completion/cancel races, ordered duplicate log delivery, and HTTP authentication/CSRF checks.
- `scripts/system_test.py --faults`: four languages, bounded adversarial isolation cases, worker SIGKILL recovery and duplicate notification delivery.
- Playwright: desktop execution/history/log replay/lessons and mobile layout with inert HTML-shaped program output.
- Open-loop benchmark client: admission and execution outcomes, dispatch lag, throughput and p50/p95; p99 is withheld when fewer than 1,000 terminal observations exist.
- Compose one-command launch, Java 21 packaged images, Podman compatibility, multi-computer SSH profile, and backup/restore.

The implementation workspace has no Docker/Podman daemon, so these deployment checks have not been executed here. The public CI workflow runs the Docker/Java 21/integration/browser/fault gates on a real Ubuntu runner. Record its actual conclusion and run URL here after it completes; do not label this release fully runtime-verified before that evidence exists. A local Chromium download also failed at the browser CDN, so no screenshot was fabricated.

No latency, throughput, p99, scaling gain, security guarantee, or multi-host result is claimed without a corresponding run. The [benchmark targets](benchmarks.md) and [roadmap criteria](roadmap.md) remain experiments to execute on the intended hardware.
