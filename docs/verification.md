# Starter verification — September 8, 2026

This is an intentionally incomplete learning starter, not a working execution platform.

## Checks actually performed

- Parsed both Maven POM files as XML.
- Parsed the Compose YAML and checked its read-only source/build arrangement, pinned image reference, absence of published ports, and default test command.
- Checked POSIX shell syntax for both scripts.
- Exercised launcher help, unsupported commands and invalid-engine rejection.
- Exercised launcher argument forwarding and nonzero exit-code propagation with an explicitly labeled test double. This did not start containers.
- Compiled all three production Java files and all three JUnit test files using OpenJDK 17.0.20 with `--release 17`, against the JUnit Platform Console Standalone 1.13.4 distribution.
- Ran `LifecycleInputTest`: **2 passed, 0 failed**.
- Ran the complete discovered suite: **16 tests, 2 passed, 14 expected failures, 0 skipped**. Each failure traces to a marked TODO, including the incorrect exception type reported by the illegal-transition test while the placeholder is present.

The methods intentionally throw `UnsupportedOperationException` until the student replaces them. That is an honest exercise marker, not production behavior. Invalid transitions in the completed implementation must use `IllegalStateException` as specified.

## Checks not completed here

The project targets Java 21. Neither a JDK 21 executable nor Podman/Docker was available in the verification environment, so the Java 21 container command has **not** been end-to-end verified. Source compatibility was checked using Java 17 because these files use no newer language features; that does not substitute for testing the intended build environment.

An attempted Maven 3.9.11 build could not resolve the JUnit BOM because Maven encountered a DNS failure for Maven Central in this environment. The Java/JUnit checks above were performed separately using the console distribution downloaded from Maven Central. They do not claim that the full Maven resolution/plugin lifecycle ran successfully here.

The pinned Maven/Temurin image index and amd64/arm64 variants were confirmed in the official image metadata. Metadata availability does not prove an image has no vulnerabilities or that a particular Compose provider will run it correctly.

## First checks on your machine

1. Run `./codegrid check` with the documented engine/Compose prerequisites. Expect two passing tests.
2. Run `./codegrid test`. Expect the unfinished exercise checks to fail.
3. Implement the two methods, add your own tests, and rerun the full suite.

If the first command fails before tests run, send the exact error. We will fix the environment/build issue separately from your lifecycle implementation.

No compiler sandbox, real lease, database concurrency, resource isolation, live API or performance benchmark has been implemented or validated in this milestone.
