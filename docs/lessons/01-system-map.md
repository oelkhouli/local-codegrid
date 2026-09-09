# 1. The system map

A submission is saved source code plus test inputs and expected outputs. A job is the request to evaluate that submission. An attempt is one worker's effort to complete the job. These are separate records because a crashed attempt must not erase the user's request or its history.

The React browser calls the Spring API. The API authenticates the user and commits the submission, job, idempotency record and first event together. A background coordinator chooses an eligible worker. The worker asks the API for its assignment, obtains current authority and creates disposable containers through the local engine. Expected answers stay outside those containers. The worker compares captured bytes, reports a verdict and removes its runtime objects. A separate janitor handles abandoned executions.

PostgreSQL holds authoritative state. Redis holds short-lived cached submissions, rate-limit buckets and assignment notifications. Prometheus collects observations; Grafana displays them. The browser receives persisted events over a WebSocket.

Read `JobService.submit`, `Coordinator.dispatch`, `WorkerService.assignment`, and `WorkerMain.execute` in that order. In Java, a record describes a data value; a service coordinates operations. Spring constructs services and supplies their dependencies. It does not implement this project's scheduling algorithm.

Follow a Python program that prints 42. Name the transaction that accepts it, the transaction that assigns it, and the transaction that makes its result final. Code execution happens between transactions, so database locks are not held while the program runs.

**Exercise:** draw the components from memory. Mark which can access the runtime socket, which can read passwords, and which receive expected answers.

**Explain it:** “The system can repeat execution after failure, but only an authorized attempt can commit a new final result. PostgreSQL determines authority; a queue notification does not.”

**Check yourself:** If Redis loses its queue, where is the job? It remains in PostgreSQL and workers can recover their assignments by polling the API.
