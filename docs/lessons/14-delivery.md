# 14. Delivery and local operations

A fresh clone starts with `./codegrid up` on a Unix shell or `./codegrid.ps1 up` in PowerShell. A documented container engine, Compose provider and Linux runtime are prerequisites. The launcher generates local secrets, builds the application and language images, starts services and waits for readiness and enforcement probes.

The application runs with no cloud runtime services. Initial builds download public packages and images. Docker Desktop is not required: Docker Engine or Podman can supply the runtime. Windows and macOS need a Linux VM; its allocated CPU and memory are the capacity visible to this platform.

All application images use pinned base-image digests. Pinning makes a reference reproducible; it does not make an image permanently secure. Review and scan updates, rebuild the language images and re-run the enforcement and contract tests. Maven and npm lock/version files describe the build dependencies.

`./codegrid scale 3` adds worker processes, not CPU cores. Workers on one engine must share one node ID and ledger volume. A second existing computer needs its own node identity, ledger and budget. The optional LAN setup uses an SSH tunnel, keeping the default public bindings on loopback.

Stopping with `down` preserves volumes. A PostgreSQL dump preserves application data but does not contain the plaintext secrets from `.env` or the node ledger volume. Follow the restore guide, stop the old deployment first and never attach unrelated ledgers to the same node identity.

**Exercise:** perform a fresh-clone launch on the documented engine, run the smoke tests, create a backup and explain the restore prerequisites. Review the generated files that must stay out of Git.

**Explain it:** “Compose reproduces processes and networks. It does not turn one physical computer into a highly available cluster.”

Read `docs/operations.md` before modifying ports, credentials or volumes. Use the Git commit and CI run to identify exactly which build you tested.
