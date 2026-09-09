export type Language = "JAVA" | "PYTHON" | "CPP" | "JAVASCRIPT";
export type User = { id: string; username: string; role: string; csrf: string };
export type Submission = {
  language: Language;
  source: string;
  tests: { input: string; expected: string }[];
  mode: "RUN" | "BENCHMARK";
};
export type CaseResult = {
  case: number;
  repetition: number;
  verdict: string;
  elapsed_ms: number;
  stdout: string;
  stderr: string;
  sampled_memory_bytes: number | null;
};
export type Attempt = {
  generation: number;
  node_id: string;
  state: string;
  cleaned: boolean;
  expected_ms: number;
  image_id: string;
  result: null | { verdict: string; cases: CaseResult[]; measurement: string };
};
export type Job = {
  id: string;
  state: string;
  verdict: string | null;
  generation: number;
  created_at: string;
  finished_at: string | null;
  submission: Submission;
  attempts: Attempt[];
};
export type Summary = {
  id: string;
  state: string;
  verdict: string | null;
  language: Language;
  mode: string;
  created_at: string;
};
export type Node = {
  id: string;
  cpu_budget: number;
  memory_budget: number;
  slots: number;
  speed: number;
  load: number;
  heartbeat: string | null;
  quarantined: boolean;
  reserved_slots: number;
  workers: number;
};
export type Overview = {
  total: number;
  active: number;
  accepted: number;
  p50_ms: number | null;
  p95_ms: number | null;
  p99_ms: number | null;
};
export type LogEvent = {
  seq: number;
  kind: string;
  data: { stream?: string; text?: string; generation?: number };
};
let csrf = "";
let sessionVersion = 0;
export function setSession(user: User | null) {
  sessionVersion++;
  csrf = user?.csrf ?? "";
}
export async function api<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const version = sessionVersion;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(path, {
      ...options,
      credentials: "same-origin",
      signal: controller.signal,
      headers: {
        "Content-Type": "application/json",
        "X-CSRF-Token": csrf,
        ...options.headers,
      },
    });
    const data = await response.json();
    if (!response.ok) {
      if (response.status === 401 && version === sessionVersion)
        window.dispatchEvent(new Event("session-expired"));
      throw new Error(data.error ?? `Request failed (${response.status})`);
    }
    return data as T;
  } finally {
    clearTimeout(timeout);
  }
}
export function post<T>(
  path: string,
  body: unknown = {},
  headers: Record<string, string> = {},
) {
  return api<T>(path, { method: "POST", body: JSON.stringify(body), headers });
}
export const labels: Record<Language, string> = {
  JAVA: "Java",
  PYTHON: "Python",
  CPP: "C++",
  JAVASCRIPT: "JavaScript",
};
export const examples: Record<
  Language,
  { source: string; input: string; expected: string }
> = {
  JAVA: {
    source:
      "import java.util.Scanner;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner input = new Scanner(System.in);\n        int a = input.nextInt();\n        int b = input.nextInt();\n        System.out.println(a + b);\n    }\n}\n",
    input: "12 30\n",
    expected: "42\n",
  },
  PYTHON: {
    source:
      "# Two inputs. One answer.\na, b = map(int, input().split())\nprint(a + b)\n",
    input: "12 30\n",
    expected: "42\n",
  },
  CPP: {
    source:
      '#include <iostream>\n\nint main() {\n    int a, b;\n    std::cin >> a >> b;\n    std::cout << a + b << "\\n";\n    return 0;\n}\n',
    input: "12 30\n",
    expected: "42\n",
  },
  JAVASCRIPT: {
    source:
      'const fs = require("node:fs");\nconst [a, b] = fs.readFileSync(0, "utf8")\n  .trim().split(/\\s+/).map(Number);\n\nconsole.log(a + b);\n',
    input: "12 30\n",
    expected: "42\n",
  },
};
export function initial(language: Language): Submission {
  const x = examples[language];
  return {
    language,
    source: x.source,
    tests: [{ input: x.input, expected: x.expected }],
    mode: "RUN",
  };
}
