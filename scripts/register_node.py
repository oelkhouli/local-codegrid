#!/usr/bin/env python3
"""Local administrator tool: write a new node credential to an owner-only file."""
import argparse
import os
from pathlib import Path
from client import Client, environment

parser = argparse.ArgumentParser()
parser.add_argument('node_id')
parser.add_argument('--cpu', type=int, default=1000, help='millicores reserved for sandboxes')
parser.add_argument('--memory', type=int, default=536870912, help='bytes reserved for sandboxes')
parser.add_argument('--slots', type=int, default=1)
parser.add_argument('--speed', type=float, default=1.0)
parser.add_argument('--output', default='.env.node')
args = parser.parse_args()
path = Path(args.output)
# Reserve the file before creating a token, so existing secrets cannot be overwritten.
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
try:
    client = Client()
    client.login('admin', environment()['ADMIN_PASSWORD'])
    result = client.request('/api/admin/nodes', {'id':args.node_id, 'cpu':args.cpu, 'memory':args.memory, 'slots':args.slots, 'speed':args.speed})
    with os.fdopen(fd, 'w') as stream:
        fd = None
        stream.write('NODE_ID=' + result['id'] + '\nNODE_TOKEN=' + result['token'] + '\n')
    print('Node registered. Credential file written to ' + str(path) + '; transfer it securely and do not commit it.')
finally:
    if fd is not None:
        os.close(fd)
        if path.stat().st_size == 0:
            path.unlink()
