#!/usr/bin/env python3
"""Publish counts and bounded benchmark data to the free Actions job summary."""
import json
import os
from pathlib import Path
import xml.etree.ElementTree as ET
reports = list(Path('backend').glob('**/target/surefire-reports/TEST-*.xml')) + list(Path('backend').glob('**/target/failsafe-reports/TEST-*.xml'))
lines = ['## Verification', '', '| Suite | Tests | Failures | Errors | Skipped |', '|---|---:|---:|---:|---:|']
for report in reports:
    root = ET.parse(report).getroot()
    lines.append('| ' + root.get('name', report.stem) + ' | ' + ' | '.join(root.get(k, '0') for k in ['tests', 'failures', 'errors', 'skipped']) + ' |')
for name in ['system-tests.json', 'ci-benchmark.json']:
    path = Path('artifacts') / name
    if path.exists():
        data = json.loads(path.read_text())
        if isinstance(data, dict):
            data = {k:v for k,v in data.items() if k not in ('samples','node_budgets')}
        lines.extend(['', '### ' + name, '', '```json', json.dumps(data, indent=2)[:12000], '```'])
lines.extend(['', 'No artifact/cache uploads. Full browser reports and traces are retained only in local test runs.'])
summary = '\n'.join(lines) + '\n'
if os.environ.get('GITHUB_STEP_SUMMARY'):
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
        stream.write(summary)
else:
    print(summary)
