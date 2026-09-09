import { useEffect, useRef, useState, type FormEvent } from "react";
import Markdown from "react-markdown";
import {
  api,
  post,
  setSession,
  initial,
  labels,
  type User,
  type Language,
  type Job,
  type Summary,
  type Node,
  type Overview,
  type Submission,
  type LogEvent,
} from "./api";

const terminal = (state: string) => ["FINISHED", "CANCELLED"].includes(state);
const pretty = (s: string) => s.toLowerCase().replaceAll("_", " ");
const millis = (n: number | null | undefined) =>
  n == null
    ? "—"
    : n < 1000
      ? `${Math.round(n)} ms`
      : `${(n / 1000).toFixed(2)} s`;
const when = (s: string) =>
  new Date(s).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
const chapters = [
  [
    "01-system-map.md",
    "The system map",
    "Follow one submission through the whole platform.",
  ],
  [
    "02-state-and-transactions.md",
    "State and transactions",
    "Keep one authoritative final decision.",
  ],
  [
    "03-idempotency.md",
    "Idempotency and admission",
    "Make a retried HTTP request safe.",
  ],
  [
    "04-leases-and-fencing.md",
    "Leases and fencing",
    "Understand why an expired worker cannot win.",
  ],
  [
    "05-scheduling.md",
    "Hardware-aware scheduling",
    "Compare estimates, capacity and fairness.",
  ],
  [
    "06-retries-and-outbox.md",
    "Retries and the outbox",
    "Recover without pretending delivery happens once.",
  ],
  [
    "07-sandbox.md",
    "The execution boundary",
    "Follow every restriction to its enforcement point.",
  ],
  [
    "08-cleanup.md",
    "Crashes and cleanup",
    "Separate logical ownership from physical processes.",
  ],
  [
    "09-security.md",
    "Authentication and authorization",
    "Trace sessions, CSRF, ownership and rate limits.",
  ],
  [
    "10-live-logs.md",
    "Durable live logs",
    "Reconnect without losing persisted events.",
  ],
  [
    "11-data-and-cache.md",
    "Data and caching",
    "Read the indexes and failure behavior.",
  ],
  [
    "12-metrics.md",
    "Measurements that mean something",
    "Interpret throughput and latency percentiles.",
  ],
  [
    "13-testing.md",
    "Testing and fault injection",
    "Prove the important failure cases.",
  ],
  [
    "14-delivery.md",
    "Compose, CI and local operations",
    "Launch, scale, back up and troubleshoot.",
  ],
  [
    "15-interview.md",
    "Explain and defend the project",
    "Practice an honest technical walkthrough.",
  ],
];
function Badge({ state }: { state: string }) {
  return (
    <span
      className={`badge ${state === "ACCEPTED" ? "good" : state === "RUNNING" || state === "LEASED" ? "live" : state === "QUEUED" || state === "RETRY_WAIT" ? "waiting" : state === "FINISHED" ? "neutral" : "bad"}`}
    >
      <i />
      {pretty(state)}
    </span>
  );
}
function Mark() {
  return (
    <span className="mark" aria-hidden="true">
      <i />
      <i />
      <i />
      <i />
    </span>
  );
}
function Login({ onLogin }: { onLogin: (u: User) => void }) {
  const [name, setName] = useState(""),
    [password, setPassword] = useState(""),
    [register, setRegister] = useState(false),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      if (register)
        await post("/api/auth/register", { username: name, password });
      const u = await post<User>("/api/auth/login", {
        username: name,
        password,
      });
      onLogin(u);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <main className="login-page">
      <section className="login-story">
        <div className="brand">
          <Mark /> CodeGrid <span>LOCAL</span>
        </div>
        <div>
          <p className="eyebrow">A SMALL DISTRIBUTED SYSTEM, ON YOUR DESK</p>
          <h1>
            Your code.
            <br />
            Your machine.
          </h1>
          <p className="intro">
            Compile, test and measure across four languages. Watch your workers
            do the work.
          </p>
          <div className="language-list">
            Java <b>·</b> Python <b>·</b> C++ <b>·</b> JavaScript
          </div>
        </div>
        <p className="quiet">Local execution. No external runtime services.</p>
      </section>
      <section className="login-form">
        <form onSubmit={submit}>
          <p className="eyebrow">YOUR LOCAL WORKSPACE</p>
          <h2>{register ? "Create an account" : "Welcome back"}</h2>
          <p className="muted">
            {register
              ? "Choose a username and a password of at least 12 characters."
              : "Sign in with your local account to start a run."}
          </p>
          <label>
            Username
            <input
              autoComplete="username"
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
              pattern="[A-Za-z0-9_]{3,32}"
            />
          </label>
          <label>
            Password
            <input
              type="password"
              autoComplete={register ? "new-password" : "current-password"}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              minLength={12}
            />
          </label>
          {error && (
            <p role="alert" className="error">
              {error}
            </p>
          )}
          <button className="primary full" disabled={busy}>
            {busy
              ? "Connecting…"
              : register
                ? "Create account"
                : "Open workspace"}{" "}
            <span>→</span>
          </button>
          <button
            className="text-button full"
            type="button"
            onClick={() => {
              setRegister(!register);
              setError("");
            }}
          >
            {register
              ? "Already have an account? Sign in"
              : "New here? Create a local account"}
          </button>
        </form>
      </section>
    </main>
  );
}
export default function App() {
  const [user, setUser] = useState<User | null>(null),
    [loading, setLoading] = useState(true),
    [tab, setTab] = useState("workspace");
  const [draft, setDraft] = useState<Submission>(initial("PYTHON")),
    [job, setJob] = useState<Job | null>(null),
    [history, setHistory] = useState<Summary[]>([]),
    [nodes, setNodes] = useState<Node[]>([]),
    [overview, setOverview] = useState<Overview | null>(null);
  const [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [events, setEvents] = useState<LogEvent[]>([]),
    [connected, setConnected] = useState(false),
    [resultTab, setResultTab] = useState("logs");
  const [chapter, setChapter] = useState<string | null>(null),
    [lesson, setLesson] = useState("");
  const retry = useRef<{ payload: string; key: string } | null>(null),
    logBox = useRef<HTMLPreElement>(null),
    numbers = useRef<HTMLPreElement>(null);
  const selected = useRef<string | null>(null);
  function login(u: User | null) {
    setSession(u);
    setUser(u);
    if (!u) {
      setJob(null);
      setEvents([]);
      setHistory([]);
      setNodes([]);
      setOverview(null);
    }
  }
  useEffect(() => {
    api<User>("/api/session")
      .then(login)
      .catch(() => {})
      .finally(() => setLoading(false));
    const expired = () => login(null);
    window.addEventListener("session-expired", expired);
    return () => window.removeEventListener("session-expired", expired);
  }, []);
  async function refresh() {
    const [h, n, o] = await Promise.all([
      api<Summary[]>("/api/jobs"),
      api<Node[]>("/api/nodes"),
      api<Overview>("/api/overview"),
    ]);
    setHistory(h);
    setNodes(n);
    setOverview(o);
  }
  useEffect(() => {
    if (!user) return;
    let alive = true;
    const update = () =>
      refresh().catch((e) => {
        if (alive) setError((e as Error).message);
      });
    void update();
    const timer = setInterval(update, 4000);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, [user]);
  useEffect(() => {
    selected.current = job?.id ?? null;
  }, [job?.id]);
  useEffect(() => {
    if (!job || !user) return;
    const id = job.id;
    let alive = true,
      cursor = 0,
      reconnect: ReturnType<typeof setTimeout>,
      socket: WebSocket;
    let delay = 500;
    setEvents([]);
    const updateJob = () =>
      api<Job>(`/api/jobs/${id}`)
        .then((j) => {
          if (alive && selected.current === id) setJob(j);
        })
        .catch(() => {});
    function connect() {
      if (!alive) return;
      socket = new WebSocket(
        `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws/jobs/${id}?after=${cursor}`,
      );
      socket.onopen = () => {
        if (alive) setConnected(true);
        delay = 500;
      };
      socket.onmessage = (e) => {
        const event = JSON.parse(e.data) as LogEvent;
        if (event.seq <= cursor) return;
        cursor = event.seq;
        setEvents((old) => [...old, event]);
        if (event.kind !== "LOG") {
          void updateJob();
          void refresh().catch(() => {});
        }
      };
      socket.onclose = () => {
        if (alive) {
          setConnected(false);
          reconnect = setTimeout(connect, delay);
          delay = Math.min(10000, delay * 2);
        }
      };
      socket.onerror = () => socket.close();
    }
    connect();
    const poll = setInterval(updateJob, 2500);
    return () => {
      alive = false;
      clearTimeout(reconnect);
      clearInterval(poll);
      socket?.close();
      setConnected(false);
    };
  }, [job?.id, user?.id]);
  useEffect(() => {
    if (logBox.current) logBox.current.scrollTop = logBox.current.scrollHeight;
  }, [events]);
  useEffect(() => {
    if (!chapter) return;
    let alive = true;
    setLesson("Loading lesson…");
    fetch("/lessons/" + chapter)
      .then((r) => {
        if (!r.ok) throw new Error("Lesson unavailable");
        return r.text();
      })
      .then((t) => {
        if (alive) setLesson(t);
      })
      .catch((e) => {
        if (alive) setLesson((e as Error).message);
      });
    return () => {
      alive = false;
    };
  }, [chapter]);
  async function run() {
    setBusy(true);
    setError("");
    const payload = JSON.stringify(draft);
    if (retry.current?.payload !== payload)
      retry.current = { payload, key: crypto.randomUUID() };
    try {
      const result = await post<{ id: string }>("/api/jobs", draft, {
        "Idempotency-Key": retry.current.key,
      });
      retry.current = null;
      const next = await api<Job>("/api/jobs/" + result.id);
      selected.current = next.id;
      setJob(next);
      setResultTab("logs");
      await refresh();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  async function open(id: string) {
    try {
      const next = await api<Job>("/api/jobs/" + id);
      selected.current = id;
      setJob(next);
      setDraft(next.submission);
      setTab("workspace");
      setError("");
    } catch (e) {
      setError((e as Error).message);
    }
  }
  async function cancel() {
    if (!job) return;
    try {
      await post("/api/jobs/" + job.id + "/cancel");
      setJob(await api<Job>("/api/jobs/" + job.id));
      await refresh();
    } catch (e) {
      setError((e as Error).message);
    }
  }
  const online = nodes.reduce(
    (sum, n) =>
      sum +
      (n.heartbeat && Date.now() - Date.parse(n.heartbeat) < 15000
        ? n.workers
        : 0),
    0,
  );
  const logs = events.filter((e) => e.kind === "LOG");
  const attempt = job?.attempts.at(-1);
  const results = attempt?.result?.cases ?? [];
  const sourceBytes = new TextEncoder().encode(draft.source).length;
  if (loading)
    return (
      <div className="loading">
        <Mark />
        <p>Opening your workspace…</p>
      </div>
    );
  if (!user) return <Login onLogin={login} />;
  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          <Mark />
          <div>
            CodeGrid<small>LOCAL WORKSPACE</small>
          </div>
        </div>
        <nav aria-label="Main navigation">
          {[
            ["workspace", "⌘", "Workspace"],
            ["runs", "≡", "Run history"],
            ["workers", "▦", "Workers"],
            ["learn", "◇", "Learning path"],
          ].map(([key, icon, label]) => (
            <button
              key={key}
              className={tab === key ? "nav active" : "nav"}
              onClick={() => setTab(key)}
            >
              <span aria-hidden="true">{icon}</span>
              {label}
              {key === "runs" && <em>{overview?.total ?? 0}</em>}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="local-status">
            <i className={online ? "dot on" : "dot"} />
            <div>
              {online ? `${online} workers online` : "Waiting for workers"}
              <small>Local runtime</small>
            </div>
          </div>
          <a
            className="grafana-link"
            href={`${location.protocol}//${location.hostname}:3000`}
            target="_blank"
            rel="noreferrer"
          >
            Open Grafana <span>↗</span>
          </a>
          <div className="account">
            <span className="avatar">
              {user.username.charAt(0).toUpperCase()}
            </span>
            <div>
              {user.username}
              <small>
                {user.role === "ADMIN" ? "Administrator" : "Local account"}
              </small>
            </div>
            <button
              className="icon-button"
              aria-label="Sign out"
              title="Sign out"
              onClick={() =>
                post("/api/logout")
                  .then(() => login(null))
                  .catch((e) => setError((e as Error).message))
              }
            >
              ↪
            </button>
          </div>
        </div>
      </aside>
      <main className="main">
        <header className="topbar">
          <div className="breadcrumb">
            Local CodeGrid <span>/</span>{" "}
            {tab === "runs"
              ? "Run history"
              : tab === "learn"
                ? "Learning path"
                : tab === "workers"
                  ? "Workers"
                  : "Workspace"}
          </div>
          <span className="local-pill">
            <i className="dot on" /> Runs on your hardware
          </span>
        </header>
        <div className="page">
          <div className="page-heading">
            <div>
              <p className="eyebrow">
                {tab === "workspace"
                  ? "BUILD. RUN. UNDERSTAND."
                  : tab === "learn"
                    ? "UNDER THE HOOD"
                    : "YOUR LOCAL SYSTEM"}
              </p>
              <h1>
                {tab === "workspace"
                  ? "Execution workspace"
                  : tab === "runs"
                    ? "Every run, accounted for."
                    : tab === "workers"
                      ? "The machines behind your runs."
                      : "Learn what you built."}
              </h1>
              <p className="muted">
                {tab === "workspace"
                  ? "A small program. A clear result. The whole journey in view."
                  : tab === "runs"
                    ? "Inspect results and revisit earlier submissions."
                    : tab === "workers"
                      ? "Shared node budgets keep worker replicas within the available capacity."
                      : "Follow the code, reproduce the failures and practice explaining the decisions."}
              </p>
            </div>
            {tab === "workspace" && (
              <button
                className="secondary"
                onClick={() => {
                  setDraft(initial(draft.language));
                  setError("");
                }}
              >
                ↺ Reset example
              </button>
            )}
          </div>
          {error && (
            <div className="error-banner" role="alert">
              {error}
              <button aria-label="Dismiss error" onClick={() => setError("")}>
                ×
              </button>
            </div>
          )}
          {tab !== "learn" && (
            <div className="stats">
              <Stat
                label="Runs in 24 hours"
                value={String(overview?.total ?? 0)}
                note={`${overview?.accepted ?? 0} accepted`}
              />
              <Stat
                label="Active jobs"
                value={String(overview?.active ?? 0)}
                note="Queued or in progress"
              />
              <Stat
                label="p50 completion"
                value={millis(overview?.p50_ms)}
                note="Includes queue and compilation"
              />
              <Stat
                label="p95 / p99"
                value={`${millis(overview?.p95_ms)} / ${millis(overview?.p99_ms)}`}
                note="Your completed runs · 24 hours"
              />
            </div>
          )}
          {tab === "workspace" && (
            <>
              <div className="workbench">
                <section className="panel editor-panel">
                  <div className="panel-heading">
                    <h2>Source code</h2>
                    <span className="subtle">
                      Single file · standard library
                    </span>
                  </div>
                  <div
                    className="language-tabs"
                    role="group"
                    aria-label="Language"
                  >
                    {(Object.keys(labels) as Language[]).map((language) => (
                      <button
                        key={language}
                        aria-pressed={draft.language === language}
                        className={
                          draft.language === language ? "selected" : ""
                        }
                        onClick={() => setDraft(initial(language))}
                      >
                        {labels[language]}
                      </button>
                    ))}
                  </div>
                  <div className="filebar">
                    <span className="file-dot" />
                    {draft.language === "JAVA"
                      ? "Main.java"
                      : draft.language === "PYTHON"
                        ? "main.py"
                        : draft.language === "CPP"
                          ? "main.cpp"
                          : "main.js"}
                    <span>{sourceBytes.toLocaleString()} / 32,768 bytes</span>
                  </div>
                  <div className="code-editor">
                    <pre aria-hidden="true" ref={numbers}>
                      {draft.source
                        .split("\n")
                        .map((_, i) => i + 1)
                        .join("\n")}
                    </pre>
                    <textarea
                      aria-label="Source code"
                      spellCheck={false}
                      value={draft.source}
                      onScroll={(e) => {
                        if (numbers.current)
                          numbers.current.scrollTop = e.currentTarget.scrollTop;
                      }}
                      onChange={(e) =>
                        setDraft({ ...draft, source: e.target.value })
                      }
                    />
                  </div>
                  <div className="execution-limits">
                    <span>1 CPU</span>
                    <span>512 MiB</span>
                    <span>20 s per case container</span>
                    <span>No network</span>
                  </div>
                </section>
                <section className="panel test-panel">
                  <div className="panel-heading">
                    <h2>Test cases</h2>
                    <button
                      className="text-button"
                      disabled={draft.tests.length >= 3}
                      onClick={() =>
                        setDraft({
                          ...draft,
                          tests: [...draft.tests, { input: "", expected: "" }],
                        })
                      }
                    >
                      + Add case
                    </button>
                  </div>
                  <div className="test-cases">
                    {draft.tests.map((test, index) => (
                      <div className="test-case" key={index}>
                        <div className="case-heading">
                          <strong>
                            <span>{String(index + 1).padStart(2, "0")}</span>{" "}
                            Test case
                          </strong>
                          {draft.tests.length > 1 && (
                            <button
                              className="icon-button"
                              aria-label={`Remove case ${index + 1}`}
                              onClick={() =>
                                setDraft({
                                  ...draft,
                                  tests: draft.tests.filter(
                                    (_, i) => i !== index,
                                  ),
                                })
                              }
                            >
                              ×
                            </button>
                          )}
                        </div>
                        <label>
                          Standard input
                          <textarea
                            aria-label={`Input ${index + 1}`}
                            spellCheck={false}
                            value={test.input}
                            onChange={(e) =>
                              setDraft({
                                ...draft,
                                tests: draft.tests.map((t, i) =>
                                  i === index
                                    ? { ...t, input: e.target.value }
                                    : t,
                                ),
                              })
                            }
                          />
                        </label>
                        <label>
                          Expected output
                          <textarea
                            aria-label={`Expected output ${index + 1}`}
                            spellCheck={false}
                            value={test.expected}
                            onChange={(e) =>
                              setDraft({
                                ...draft,
                                tests: draft.tests.map((t, i) =>
                                  i === index
                                    ? { ...t, expected: e.target.value }
                                    : t,
                                ),
                              })
                            }
                          />
                        </label>
                      </div>
                    ))}
                  </div>
                  <div className="run-options">
                    <label className="check-label">
                      <input
                        type="checkbox"
                        checked={draft.mode === "BENCHMARK"}
                        onChange={(e) =>
                          setDraft({
                            ...draft,
                            mode: e.target.checked ? "BENCHMARK" : "RUN",
                          })
                        }
                      />
                      <span>
                        Benchmark this program
                        <small>
                          Three cold compile-and-run repetitions per case
                        </small>
                      </span>
                    </label>
                    <button
                      className="primary full"
                      disabled={
                        busy || sourceBytes > 32768 || !draft.source.trim()
                      }
                      onClick={run}
                    >
                      {busy
                        ? "Submitting…"
                        : draft.mode === "BENCHMARK"
                          ? "Start benchmark"
                          : "Run tests"}
                      <span>▷</span>
                    </button>
                  </div>
                </section>
              </div>
              <section className="panel output-panel">
                <div className="panel-heading">
                  <div className="output-title">
                    <h2>Execution</h2>
                    {job && <Badge state={job.verdict ?? job.state} />}
                  </div>
                  <div className="output-actions">
                    {job && (
                      <span className="subtle mono">{job.id.slice(0, 8)}</span>
                    )}
                    {job && !terminal(job.state) && (
                      <button className="danger-button" onClick={cancel}>
                        Cancel run
                      </button>
                    )}
                  </div>
                </div>
                <div className="output-tabs">
                  {["logs", "results", "attempts"].map((t) => (
                    <button
                      key={t}
                      className={resultTab === t ? "selected" : ""}
                      onClick={() => setResultTab(t)}
                    >
                      {t === "logs"
                        ? "Live output"
                        : t === "results"
                          ? "Test results"
                          : "Attempt timeline"}
                    </button>
                  ))}
                  <span className="connection">
                    <i className={connected ? "dot on" : "dot"} />
                    {job
                      ? connected
                        ? "Connected"
                        : "Reconnecting…"
                      : "Ready when you are"}
                  </span>
                </div>
                {!job ? (
                  <div className="empty-state">
                    <span className="empty-glyph">▷</span>
                    <h3>Your next result starts here.</h3>
                    <p>Run the example or bring a program of your own.</p>
                  </div>
                ) : resultTab === "logs" ? (
                  <pre
                    className="console"
                    ref={logBox}
                    aria-label="Execution logs"
                  >
                    {logs.length ? (
                      logs.map((e) => (
                        <span
                          className={e.data.stream === "stderr" ? "stderr" : ""}
                          key={e.seq}
                        >
                          {e.data.text}
                        </span>
                      ))
                    ) : (
                      <span className="console-placeholder">
                        {terminal(job.state)
                          ? "No output was captured for this run."
                          : "Waiting for output…"}
                      </span>
                    )}
                  </pre>
                ) : resultTab === "results" ? (
                  <div className="results-wrap">
                    {results.length ? (
                      <>
                        <table>
                          <thead>
                            <tr>
                              <th>Case / repetition</th>
                              <th>Result</th>
                              <th>Cold elapsed</th>
                              <th>Sampled peak memory</th>
                            </tr>
                          </thead>
                          <tbody>
                            {results.map((r, i) => (
                              <tr key={i}>
                                <td>
                                  {r.case} / {r.repetition}
                                </td>
                                <td>
                                  <Badge state={r.verdict} />
                                </td>
                                <td className="mono">{millis(r.elapsed_ms)}</td>
                                <td>
                                  {r.sampled_memory_bytes == null
                                    ? "Not sampled"
                                    : `${(r.sampled_memory_bytes / 1048576).toFixed(1)} MiB`}
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                        <p className="measurement-note">
                          Elapsed time includes container startup, compilation
                          and execution. Memory is the highest observed sample,
                          so a short run may have no sample.
                        </p>
                      </>
                    ) : (
                      <div className="empty-state small">
                        <p>Results appear when an attempt reports back.</p>
                      </div>
                    )}
                  </div>
                ) : (
                  <div className="timeline">
                    {job.attempts.length ? (
                      job.attempts.map((a) => (
                        <div className="timeline-row" key={a.generation}>
                          <span className="attempt-number">{a.generation}</span>
                          <div>
                            <strong>
                              Attempt {a.generation}{" "}
                              <span className="subtle">on {a.node_id}</span>
                            </strong>
                            <p>
                              Estimated {millis(a.expected_ms)} ·{" "}
                              {a.cleaned
                                ? "Sandbox removed; reservation released"
                                : "Reservation held until cleanup is confirmed"}
                            </p>
                            <code title={a.image_id}>
                              Image {a.image_id.slice(0, 19)}…
                            </code>
                          </div>
                          <Badge state={a.result?.verdict ?? a.state} />
                        </div>
                      ))
                    ) : (
                      <div className="empty-state small">
                        <p>No worker has been assigned yet.</p>
                      </div>
                    )}
                  </div>
                )}
              </section>
            </>
          )}
          {tab === "runs" && (
            <section className="panel">
              <div className="panel-heading">
                <h2>Recent runs</h2>
                <span className="subtle">Up to 100 submissions</span>
              </div>
              {history.length ? (
                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr>
                        <th>Run</th>
                        <th>Language</th>
                        <th>Mode</th>
                        <th>Result</th>
                        <th>Submitted</th>
                        <th />
                      </tr>
                    </thead>
                    <tbody>
                      {history.map((j) => (
                        <tr key={j.id}>
                          <td className="mono">{j.id.slice(0, 8)}</td>
                          <td>{labels[j.language]}</td>
                          <td>{pretty(j.mode)}</td>
                          <td>
                            <Badge state={j.verdict ?? j.state} />
                          </td>
                          <td>{when(j.created_at)}</td>
                          <td>
                            <button
                              className="text-button"
                              onClick={() => open(j.id)}
                            >
                              Open →
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <div className="empty-state">
                  <h3>A clean slate.</h3>
                  <p>Your submissions will appear here.</p>
                  <button
                    className="secondary"
                    onClick={() => setTab("workspace")}
                  >
                    Start a run
                  </button>
                </div>
              )}
            </section>
          )}
          {tab === "workers" && (
            <div className="node-grid">
              {nodes.map((n) => (
                <section className="panel node-card" key={n.id}>
                  <div className="panel-heading">
                    <h2>▦ {n.id}</h2>
                    <Badge
                      state={
                        n.quarantined
                          ? "QUARANTINED"
                          : n.heartbeat &&
                              Date.now() - Date.parse(n.heartbeat) < 15000
                            ? "RUNNING"
                            : "OFFLINE"
                      }
                    />
                  </div>
                  <div className="node-main">
                    <div className="node-count">
                      {n.reserved_slots}
                      <span>/ {n.slots}</span>
                    </div>
                    <p className="muted">execution slots reserved</p>
                    <div className="capacity-track">
                      <i
                        style={{
                          width: `${Math.min(100, (n.reserved_slots / n.slots) * 100)}%`,
                        }}
                      />
                    </div>
                    <dl>
                      <div>
                        <dt>Worker processes</dt>
                        <dd>{n.workers}</dd>
                      </div>
                      <div>
                        <dt>CPU budget</dt>
                        <dd>{n.cpu_budget / 1000} cores</dd>
                      </div>
                      <div>
                        <dt>Memory reserved / budget</dt>
                        <dd>
                          {n.reserved_slots * 512} /{" "}
                          {Math.round(n.memory_budget / 1048576)} MiB
                        </dd>
                      </div>
                      <div>
                        <dt>System load average</dt>
                        <dd>{n.load.toFixed(2)}</dd>
                      </div>
                    </dl>
                    <p className="measurement-note">
                      All workers on this node share one resource budget.
                      Quarantine prevents new assignments while cleanup remains
                      uncertain.
                    </p>
                  </div>
                </section>
              ))}
            </div>
          )}
          {tab === "learn" &&
            (chapter ? (
              <article className="panel lesson">
                <button
                  className="text-button"
                  onClick={() => setChapter(null)}
                >
                  ← All lessons
                </button>
                <Markdown skipHtml>{lesson}</Markdown>
              </article>
            ) : (
              <>
                <div className="learning-intro">
                  <span>15 lessons</span>
                  <p>
                    Read the implementation, then close the lesson and explain
                    the invariant in your own words. Each lesson includes a code
                    trail, a failure scenario and a practical exercise.
                  </p>
                </div>
                <div className="chapter-grid">
                  {chapters.map(([file, title, description], i) => (
                    <button
                      className="panel chapter-card"
                      key={file}
                      onClick={() => setChapter(file)}
                    >
                      <span className="chapter-number">
                        {String(i + 1).padStart(2, "0")}
                      </span>
                      <div>
                        <h2>{title}</h2>
                        <p>{description}</p>
                      </div>
                      <span className="chapter-arrow">↗</span>
                    </button>
                  ))}
                </div>
              </>
            ))}
          <footer>
            CodeGrid <span>·</span> A local distributed execution lab{" "}
            <span>·</span> CPU, memory, process, filesystem, output and network
            limits
          </footer>
        </div>
      </main>
    </div>
  );
}
function Stat({
  label,
  value,
  note,
}: {
  label: string;
  value: string;
  note: string;
}) {
  return (
    <div className="stat">
      <span>{label}</span>
      <strong>{value}</strong>
      <small>{note}</small>
    </div>
  );
}
