# Local CodeGrid — milestone 1

This is a small learning starter, not the finished execution platform. It contains a plain-Java job lifecycle exercise, JUnit tests, and a containerized development test command. There is no Spring server, database, browser UI, or submitted-code executor yet.

Your first task is to implement two marked methods. The initial failing exercise tests are deliberate; there is no hidden solution or skipped test suite.

## Start here

Read [the project blueprint](docs/blueprint.md) for the MVP, ten-week roadmap, architecture diagrams, database schema, API contract, job state machine, threat model, repository plan and measurable stage gates. This first lifecycle exercise is one small part of the blueprint's foundation stage.

1. Read [the first lesson](docs/lessons/01-job-lifecycle.md).
2. Open `backend/domain/src/main/java/dev/codegrid/domain/JobState.java` and implement `isTerminal()`.
3. Open `JobLifecycle.java` in the same directory and implement `transition(...)`.
4. Add your own edge-case tests, then complete [the decision record](docs/adr/0001-job-lifecycle.md).

Keep your implementation small. No database, concurrency library or framework is needed for this exercise.

## Run the checks

With Podman and its Compose provider installed, or Docker Engine and Compose installed, run from this directory:

```bash
./codegrid check
./codegrid test
```

`check` runs two input-validation tests to check the Java/JUnit wiring. `test` runs the full suite and initially fails at the exercise TODOs. The second command must pass before this milestone is complete. Exit status is preserved; a failed test is not presented as success.

The launcher prefers Podman when both engines are installed. Select Docker explicitly with `CODEGRID_ENGINE=docker ./codegrid test`. If your unzip tool dropped executable permissions, use `sh codegrid test`.

For Windows PowerShell, the equivalent commands need no shell script:

```powershell
podman compose -f compose.dev.yaml run --rm domain-tests -Dtest=LifecycleInputTest test
podman compose -f compose.dev.yaml run --rm domain-tests test
```

Replace `podman` with `docker` if using Docker Engine. Podman on Windows/macOS needs its Linux machine running. A Compose provider is required for `podman compose`.

The development container includes Maven and JDK 21. It downloads Maven dependencies on first use, then retains them in a project-scoped cache volume. It copies the small module from a read-only source mount into temporary storage so compilation cannot create root-owned build files in your checkout. Each run copies your latest edits. No runtime socket, host credentials, or published port is needed for these tests.

Alternatively, with a local JDK 21 and Maven installed:

```bash
mvn -f backend/pom.xml -Dtest=LifecycleInputTest test
mvn -f backend/pom.xml test
```

The final platform's `./codegrid up` does not exist yet. The launcher reports that explicitly. This exercise's build container is **not** a sandbox for running untrusted submissions.

## What is provided and what you write

| Provided | Your work |
| --- | --- |
| Enum names and method signatures | Terminal-state logic and transition rules |
| Null-input validation | Rejection of illegal transitions |
| Concrete behavioral examples in JUnit | At least three additional behavioral tests |
| Lesson and decision-record questions | Explanations in your own words |
| Maven and Compose development setup | No infrastructure setup code in this exercise |

All source files use Java features available since Java 17; the project targets Java 21. Read [verification notes](docs/verification.md) for the checks actually performed on this starter and their limitations.

## Completion checklist

- Both TODO methods are implemented.
- `./codegrid test` passes with no disabled, deleted or weakened tests.
- At least three additional tests exercise observable behavior.
- The decision record explains terminality, event preconditions and the future database boundary.
- You can explain the stale-worker example without reading the lesson.

Then return `JobState.java`, `JobLifecycle.java`, your tests and the decision record for review. The next slice introduces an immutable job snapshot and real PostgreSQL ownership; it is not part of this starter.

Build-tool references: [JUnit 5 guide](https://docs.junit.org/5.13.4/user-guide/), [Maven Surefire](https://maven.apache.org/surefire/maven-surefire-plugin/), [Podman Compose](https://docs.podman.io/en/latest/markdown/podman-compose.1.html).
