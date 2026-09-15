#!/usr/bin/python3
"""Immutable launcher inside a bounded container. Expected answers stay with the worker."""
import json
import os
import pathlib
import resource
import subprocess
import sys

ROOT = pathlib.Path('/work')

def oom_count():
    try:
        return int(dict(line.split() for line in pathlib.Path('/sys/fs/cgroup/memory.events').read_text().splitlines()).get('oom_kill', 0))
    except (OSError, ValueError):
        return 0

def probe():
    status = dict(line.split(':', 1) for line in pathlib.Path('/proc/self/status').read_text().splitlines() if ':' in line)
    memory = pathlib.Path('/sys/fs/cgroup/memory.max').read_text().strip()
    pids = pathlib.Path('/sys/fs/cgroup/pids.max').read_text().strip()
    quota, period = pathlib.Path('/sys/fs/cgroup/cpu.max').read_text().split()
    mounts = [line.split() for line in pathlib.Path('/proc/mounts').read_text().splitlines()]
    work = next(m for m in mounts if m[1] == '/work')
    root = next(m for m in mounts if m[1] == '/')
    checks = {
        'non_root': os.geteuid() == 65534,
        'no_capabilities': int(status['CapEff'].strip(), 16) == 0,
        'no_new_privileges': status['NoNewPrivs'].strip() == '1',
        'seccomp': status['Seccomp'].strip() == '2',
        'memory': memory != 'max' and int(memory) <= 536870912,
        'swap': pathlib.Path('/sys/fs/cgroup/memory.swap.max').read_text().strip() == '0',
        'executable_workspace': 'noexec' not in work[3].split(','),
        'pids': pids != 'max' and int(pids) <= 128,
        'cpu': quota != 'max' and int(quota) / int(period) <= 1,
        'root_readonly': 'ro' in root[3].split(','),
        'bounded_tmpfs': work[2] == 'tmpfs' and os.statvfs('/work').f_blocks * os.statvfs('/work').f_frsize <= 67108864,
        'file_size_limit': resource.getrlimit(resource.RLIMIT_FSIZE)[0] <= 33554432,
        'network_none': set(os.listdir('/sys/class/net')) == {'lo'},
    }
    print(json.dumps(checks), flush=True)
    return 0 if all(checks.values()) else 30

def main():
    raw = sys.stdin.buffer.read(131073)
    if len(raw) > 131072:
        return 30
    data = json.loads(raw)
    if data.get('probe') is True:
        return probe()
    language = data['language']
    choices = {
        'JAVA': ('Main.java', ['javac', '-J-Xmx128m', '-J-XX:ActiveProcessorCount=1', 'Main.java'],
                 ['java', '-Xmx128m', '-XX:MaxMetaspaceSize=96m', '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=1', '-Xss512k', 'Main']),
        'PYTHON': ('main.py', ['/usr/bin/python3', '-I', '-c', "import ast,pathlib;ast.parse(pathlib.Path('main.py').read_text())"],
                   ['/usr/bin/python3', '-I', '-B', '-u', 'main.py']),
        'CPP': ('main.cpp', ['g++', '-std=c++17', '-O2', '-pipe', 'main.cpp', '-o', 'program'], ['./program']),
        'JAVASCRIPT': ('main.js', ['node', '--check', 'main.js'], ['node', '--max-old-space-size=128', '--stack-size=512', 'main.js']),
    }
    filename, compile_command, run_command = choices[language]
    source = data['source'].encode('utf-8')
    stdin = data['input'].encode('utf-8')
    if len(source) > 32768 or len(stdin) > 4096:
        return 30
    os.umask(0o077)
    os.chdir(ROOT)
    (ROOT / filename).write_bytes(source)
    (ROOT / 'stdin').write_bytes(stdin)
    before = oom_count()
    try:
        compiled = subprocess.run(compile_command, stdin=subprocess.DEVNULL, stdout=sys.stderr, stderr=sys.stderr, timeout=12)
        if oom_count() > before:
            return 22
        if compiled.returncode:
            return 20
        with (ROOT / 'stdin').open('rb') as input_file:
            completed = subprocess.run(run_command, stdin=input_file, timeout=6)
        if oom_count() > before:
            return 22
        return 0 if completed.returncode == 0 else 21
    except subprocess.TimeoutExpired:
        return 24

if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception:
        print('Execution launcher rejected the request or encountered an internal error.', file=sys.stderr)
        sys.exit(30)
