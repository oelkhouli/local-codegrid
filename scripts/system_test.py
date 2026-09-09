#!/usr/bin/env python3
"""Run ONLY against a prepared CodeGrid deployment whose worker enforcement probes passed."""
import argparse
import datetime
import json
import os
import pathlib
import subprocess
import time
import uuid
from client import Client, ROOT, environment

EXAMPLES = {
    'JAVA': 'public class Main { public static void main(String[] a) { System.out.println(42); } }',
    'PYTHON': 'print(42)\n',
    'CPP': '#include <iostream>\nint main(){std::cout << 42 << "\\n";}\n',
    'JAVASCRIPT': 'console.log(42);\n',
}
SECURITY = [
    ('network', 'PYTHON', 'import socket\ntry:\n socket.create_connection(("1.1.1.1",53),timeout=1)\n print("unexpected")\nexcept OSError:\n print("blocked")\n', 'blocked\n', 'ACCEPTED'),
    ('readonly-root', 'PYTHON', 'try:\n open("/forbidden", "w").write("x")\n print("unexpected")\nexcept OSError:\n print("blocked")\n', 'blocked\n', 'ACCEPTED'),
    ('no-host-secrets', 'PYTHON', 'import os\nprint("blocked" if not os.path.exists("/var/run/docker.sock") and not os.getenv("NODE_TOKEN") and not os.getenv("DATABASE_PASSWORD") else "unexpected")\n', 'blocked\n', 'ACCEPTED'),
    ('workspace-quota', 'PYTHON', 'try:\n for i in range(4):\n  with open(str(i),"wb") as f: f.write(b"x"*(24*1024*1024))\n print("unexpected")\nexcept OSError:\n print("bounded")\n', 'bounded\n', 'ACCEPTED'),
    ('process-limit', 'CPP', '#include <unistd.h>\n#include <sys/wait.h>\n#include <signal.h>\n#include <cstdio>\n#include <vector>\nint main(){std::vector<int> p;bool bounded=false;for(int i=0;i<256;i++){int c=fork();if(c<0){bounded=true;break;}if(c==0){pause();_exit(0);}p.push_back(c);}for(int c:p)kill(c,SIGKILL);for(int c:p)waitpid(c,nullptr,0);puts(bounded?"bounded":"unexpected");}\n', 'bounded\n', 'ACCEPTED'),
    ('memory-limit', 'PYTHON', 'x=bytearray(700*1024*1024)\nprint(len(x))\n', '', 'MEMORY_LIMIT'),
    ('output-limit', 'PYTHON', 'while True: print("x"*1024,flush=True)\n', '', 'OUTPUT_LIMIT'),
    ('wall-timeout', 'PYTHON', 'while True: pass\n', '', 'TIME_LIMIT'),
    ('compile-error', 'JAVA', 'public class Main { invalid syntax }', '', 'COMPILE_ERROR'),
    ('runtime-error', 'PYTHON', 'raise RuntimeError("example failure")\n', '', 'RUNTIME_ERROR'),
    ('wrong-answer', 'PYTHON', 'print(41)\n', '42\n', 'WRONG_ANSWER'),
]

def compose(*args):
    engine = os.environ.get('CODEGRID_ENGINE', 'docker')
    return subprocess.run([engine, 'compose', '--env-file', str(ROOT / '.env'), '-f', str(ROOT / 'compose.yaml'), *args], check=True, capture_output=True, text=True).stdout

def sql(statement):
    return compose('exec', '-T', 'postgres', 'psql', '-U', 'codegrid', '-d', 'codegrid', '-tAc', statement).strip()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    parser.add_argument('--faults', action='store_true')
    parser.add_argument('--report', default=str(ROOT / 'artifacts/system-tests.json'))
    args = parser.parse_args()
    client = Client(args.base_url)
    client.login('admin', environment()['ADMIN_PASSWORD'])
    nodes = client.request('/api/nodes')
    assert any(n['workers'] > 0 and not n['quarantined'] for n in nodes), 'No ready, enforcement-checked worker'
    checks = []
    for language, source in EXAMPLES.items():
        job = client.submit(language, source, expected='42\n')
        result = client.wait(job)
        assert result['verdict'] == 'ACCEPTED', (language, result)
        checks.append({'check': language, 'job': job, 'verdict': result['verdict']})
        print(language, 'passed', flush=True)
    for name, language, source, expected, verdict in SECURITY:
        job = client.submit(language, source, expected=expected)
        result = client.wait(job)
        assert result['verdict'] == verdict, (name, result)
        checks.append({'check': name, 'job': job, 'verdict': result['verdict']})
        print(name, 'passed', flush=True)
    if args.faults:
        job = client.submit('PYTHON', 'import time\ntime.sleep(3)\nprint(42)\n', expected='42\n')
        deadline = time.monotonic() + 30
        while client.request('/api/jobs/' + job)['state'] != 'RUNNING':
            assert time.monotonic() < deadline
            time.sleep(0.1)
        compose('kill', '-s', 'SIGKILL', 'worker')
        compose('up', '-d', '--no-deps', '--scale', 'worker=2', 'worker')
        result = client.wait(job)
        assert result['verdict'] == 'ACCEPTED' and result['generation'] >= 2, result
        checks.append({'check': 'worker-crash-recovery', 'job': job, 'attempts': result['generation']})
        print('worker-crash-recovery passed', flush=True)
        job = client.submit('PYTHON', 'import time\ntime.sleep(2)\nprint(42)\n', expected='42\n')
        deadline = time.monotonic() + 30
        while client.request('/api/jobs/' + job)['generation'] == 0:
            assert time.monotonic() < deadline
            time.sleep(0.1)
        safe = str(uuid.UUID(job))
        sql("UPDATE outbox SET published_at=NULL WHERE job_id='" + safe + "'")
        result = client.wait(job)
        assert result['verdict'] == 'ACCEPTED' and result['generation'] == 1, result
        checks.append({'check': 'duplicate-delivery', 'job': job, 'attempts': 1})
        print('duplicate-delivery passed', flush=True)
    assert sql("SELECT rolsuper FROM pg_roles WHERE rolname='codegrid_app'") == 'f'
    assert sql("SELECT count(*) FROM nodes n WHERE (SELECT count(*) FROM attempts a WHERE a.node_id=n.id AND NOT cleaned)>n.slots") == '0'
    report = {'timestamp': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'checks': checks, 'passed': len(checks), 'scope': 'real API, runtime containers and local database'}
    path = pathlib.Path(args.report);path.parent.mkdir(exist_ok=True, parents=True);path.write_text(json.dumps(report, indent=2)+'\n')
    print(str(len(checks)) + ' system checks passed; report: ' + str(path))

if __name__ == '__main__':
    main()
