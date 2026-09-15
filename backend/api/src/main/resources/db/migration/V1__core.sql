CREATE TABLE users (
 id uuid PRIMARY KEY, username varchar(32) NOT NULL UNIQUE,
 password_hash varchar(100) NOT NULL, role varchar(8) NOT NULL CHECK(role IN ('USER','ADMIN')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE sessions (
 token_hash char(64) PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 csrf varchar(64) NOT NULL, expires_at timestamptz NOT NULL
);
CREATE INDEX sessions_expiry ON sessions(expires_at);
CREATE TABLE nodes (
 id varchar(64) PRIMARY KEY, token_hash char(64) NOT NULL,
 cpu_budget integer NOT NULL CHECK(cpu_budget >= 1000), memory_budget bigint NOT NULL CHECK(memory_budget >= 536870912),
 slots integer NOT NULL CHECK(slots BETWEEN 1 AND 32), speed double precision NOT NULL DEFAULT 1 CHECK(speed > 0),
 load double precision NOT NULL DEFAULT 0, heartbeat timestamptz,
 quarantined boolean NOT NULL DEFAULT true
);
CREATE TABLE workers (
 id uuid PRIMARY KEY, node_id varchar(64) NOT NULL REFERENCES nodes(id),
 languages jsonb NOT NULL, images jsonb NOT NULL, heartbeat timestamptz NOT NULL
);
CREATE INDEX workers_fresh ON workers(node_id,heartbeat);
CREATE TABLE submissions (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES users(id),
 language varchar(12) NOT NULL CHECK(language IN ('JAVA','PYTHON','CPP','JAVASCRIPT')),
 source text NOT NULL CHECK(octet_length(source) <= 32768), tests jsonb NOT NULL,
 mode varchar(10) NOT NULL CHECK(mode IN ('RUN','BENCHMARK')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE jobs (
 id uuid PRIMARY KEY, submission_id uuid NOT NULL REFERENCES submissions(id), owner_id uuid NOT NULL REFERENCES users(id),
 state varchar(12) NOT NULL CHECK(state IN ('QUEUED','LEASED','RUNNING','RETRY_WAIT','FINISHED','CANCELLED')),
 verdict varchar(32), generation integer NOT NULL DEFAULT 0 CHECK(generation BETWEEN 0 AND 3),
 worker_id uuid REFERENCES workers(id), event_seq bigint NOT NULL DEFAULT 0, output_bytes integer NOT NULL DEFAULT 0 CHECK(output_bytes BETWEEN 0 AND 131072),
 ready_at timestamptz NOT NULL DEFAULT clock_timestamp(), deadline timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), finished_at timestamptz,
 CHECK((state IN ('FINISHED','CANCELLED')) = (finished_at IS NOT NULL)),
 CHECK((state IN ('FINISHED','CANCELLED')) = (verdict IS NOT NULL))
);
CREATE INDEX jobs_owner_history ON jobs(owner_id,created_at DESC);
CREATE INDEX jobs_due ON jobs(ready_at,created_at) WHERE state IN ('QUEUED','RETRY_WAIT');
CREATE TABLE idempotency (
 owner_id uuid NOT NULL REFERENCES users(id), key varchar(100) NOT NULL, request_hash char(64) NOT NULL,
 job_id uuid NOT NULL REFERENCES jobs(id), PRIMARY KEY(owner_id,key)
);
CREATE TABLE attempts (
 job_id uuid NOT NULL REFERENCES jobs(id), generation integer NOT NULL, worker_id uuid NOT NULL REFERENCES workers(id),
 node_id varchar(64) NOT NULL REFERENCES nodes(id), state varchar(12) NOT NULL,
 lease_until timestamptz NOT NULL, deadline timestamptz NOT NULL, started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 ended_at timestamptz, cleaned boolean NOT NULL DEFAULT false, result jsonb,
 expected_ms bigint NOT NULL, image_id varchar(100) NOT NULL, last_chunk integer NOT NULL DEFAULT 0,
 PRIMARY KEY(job_id,generation)
);
CREATE INDEX attempts_expired ON attempts(lease_until) WHERE state IN ('LEASED','RUNNING');
CREATE INDEX attempts_reservations ON attempts(node_id,worker_id) WHERE NOT cleaned;
CREATE TABLE events (
 job_id uuid NOT NULL REFERENCES jobs(id), seq bigint NOT NULL,
 kind varchar(16) NOT NULL, data jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(job_id,seq)
);
CREATE TABLE outbox (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, worker_id uuid NOT NULL, job_id uuid NOT NULL,
 generation integer NOT NULL, published_at timestamptz, created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX outbox_pending ON outbox(id) WHERE published_at IS NULL;
CREATE TABLE runtime_history (
 node_id varchar(64) NOT NULL REFERENCES nodes(id), language varchar(12) NOT NULL,
 samples bigint NOT NULL, ewma_ms double precision NOT NULL, PRIMARY KEY(node_id,language)
);
CREATE TABLE audit_log (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, actor varchar(64), action varchar(40) NOT NULL,
 target varchar(100), created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX audit_recent ON audit_log(created_at DESC);

ALTER TABLE attempts ADD COLUMN report_hash char(64);
CREATE TABLE attempt_chunks (job_id uuid NOT NULL,generation integer NOT NULL,sequence integer NOT NULL,payload_hash char(64) NOT NULL,PRIMARY KEY(job_id,generation,sequence),FOREIGN KEY(job_id,generation) REFERENCES attempts(job_id,generation));

ALTER TABLE nodes ADD COLUMN ledger_id uuid;
