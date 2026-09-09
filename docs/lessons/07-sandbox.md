# 7. The execution boundary

Read `SandboxPolicy.create()` before reading the launcher. Every execution uses fixed arguments: a registered image ID, non-root UID, no network, a read-only root, dropped capabilities, no-new-privileges, CPU and memory limits, a process limit and bounded writable tmpfs mounts. The workspace permits execution because C++ produces a native binary. Temporary storage and shared memory have separate bounds.

The worker supplies source and stdin as bounded JSON over standard input. It does not interpolate source into a shell command, mount the repository, or pass the runtime socket into the sandbox. `phase.py` chooses filenames and compiler commands from a fixed table. User-supplied package installation and compiler options are not supported.

The expected answer remains in the trusted worker. Captured stdout is compared as bytes, normalizing CRLF to LF while preserving other whitespace. UI text decoding is separate from comparison: malformed UTF-8 must not become an accepted replacement character.

Before registering, each worker runs an immutable probe inside the same restriction template. The probe checks actual cgroup values, identity, capabilities, seccomp, network interfaces and filesystem properties. An unsupported host fails closed rather than silently omitting limits.

The inner launcher applies compilation/execution deadlines. PID 1 has a fixed outer timeout, and the trusted worker adds its own elapsed-time deadline. Programs share a kernel with the runtime host, so a kernel or runtime escape remains the largest security limitation. This is a local lab, not an internet-facing hostile multi-tenant service.

**Exercise:** explain where each restriction is enforced, and run the bounded security fixtures only through the prepared platform. Inspect the workspace quota, memory, network and process-limit results.

**Explain it:** “Docker defaults are not the security policy. I explicitly set restrictions and verify their enforcement on the supported host.”

A worker or janitor with runtime-socket access is a trusted component with powerful host-level authority. Protect its token and socket accordingly.
