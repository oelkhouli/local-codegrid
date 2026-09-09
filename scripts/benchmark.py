#!/usr/bin/env python3
"""Open-loop offered load. Records admission errors and every end-to-end sample."""
import argparse
import concurrent.futures
import datetime
import json
import math
import pathlib
import platform
import secrets
import threading
import time
import urllib.error
from client import Client, ROOT, environment


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(p * len(ordered)) - 1)]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--samples', '--jobs', dest='samples', type=int, default=1000)
    parser.add_argument('--rate', type=float, default=2.0)
    parser.add_argument('--users', type=int, default=3)
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    parser.add_argument('--output', default=str(ROOT / 'artifacts/benchmark.json'))
    args = parser.parse_args()
    if not 1 <= args.samples <= 5000 or not 0 < args.rate <= 10 or not 1 <= args.users <= 3:
        parser.error('samples: 1–5000, rate: (0,10], users: 1–3')
    clients = []
    for i in range(args.users):
        client = Client(args.base_url)
        name = 'bench_' + secrets.token_hex(4)
        password = secrets.token_urlsafe(24)
        client.request('/api/auth/register', {'username': name, 'password': password})
        client.login(name, password)
        clients.append(client)
    locks = [threading.Lock() for _ in clients]
    outcomes = []
    started = time.monotonic()
    def one(index):
        client = clients[index % len(clients)]
        row = {'index': index, 'offered_at_s': index / args.rate}
        begin = time.monotonic()
        row['dispatch_lag_ms'] = max(0, (begin - started - index / args.rate) * 1000)
        try:
            # Each account's CookieJar is used under a lock. Waiting is outside that lock.
            with locks[index % len(clients)]:
                job = client.request('/api/jobs', {'language': 'PYTHON', 'source': 'print(42)\n', 'tests': [{'input':'','expected':'42\n'}], 'mode':'RUN'}, {'Idempotency-Key':secrets.token_hex(16)})['id']
            row['job'] = job
            row['admission_ms'] = (time.monotonic() - begin) * 1000
            deadline = time.monotonic() + 320
            while time.monotonic() < deadline:
                with locks[index % len(clients)]: result = client.request('/api/jobs/' + job)
                if result['state'] in ('FINISHED','CANCELLED'):
                    row.update(job=job,verdict=result['verdict'],client_latency_ms=(time.monotonic()-begin)*1000,
                               server_latency_ms=(datetime.datetime.fromisoformat(result['finished_at'].replace('Z','+00:00'))-datetime.datetime.fromisoformat(result['created_at'].replace('Z','+00:00'))).total_seconds()*1000)
                    return row
                time.sleep(0.5)
            row['error']='deadline'
        except urllib.error.HTTPError as error:
            row['poll_http_status' if 'job' in row else 'http_status']=error.code
        except Exception as error:
            row['error']=type(error).__name__
        return row
    with concurrent.futures.ThreadPoolExecutor(max_workers=32) as pool:
        futures=[]
        for index in range(args.samples):
            wait = started + index / args.rate - time.monotonic()
            if wait > 0: time.sleep(wait)
            futures.append(pool.submit(one,index))
        for future in concurrent.futures.as_completed(futures): outcomes.append(future.result())
    elapsed=time.monotonic()-started
    completed=[r for r in outcomes if 'server_latency_ms' in r]
    values=[r['server_latency_ms'] for r in completed]
    accepted=sum(r.get('verdict')=='ACCEPTED' for r in outcomes)
    nodes=clients[0].request('/api/nodes')
    report={'timestamp':datetime.datetime.now(datetime.timezone.utc).isoformat(),'client_platform':platform.platform(),'node_budgets':nodes,
            'workload':'Python print(42), cold container and interpreter startup, one case','offered_samples':args.samples,'completed_samples':len(values),
            'offered_rate_per_second':args.rate,'elapsed_seconds':elapsed,'successful_throughput_per_second':accepted/elapsed,
            'transport_or_poll_errors':sum('error' in r or 'poll_http_status' in r for r in outcomes),'admission_rejections':sum('http_status' in r for r in outcomes),'execution_failures':sum(r.get('verdict') not in (None,'ACCEPTED') for r in outcomes),
            'p50_ms':percentile(values,.5),'p95_ms':percentile(values,.95),'p99_ms':percentile(values,.99) if len(values)>=1000 else None,
            'tail_sample_warning':len(values)<1000,'percentile_method':'nearest rank; server acceptance to terminal commit; all terminal verdicts included',
            'client_p50_ms':percentile([r['client_latency_ms'] for r in completed],.5),'client_p95_ms':percentile([r['client_latency_ms'] for r in completed],.95),'client_p99_ms':percentile([r['client_latency_ms'] for r in completed],.99) if len(completed)>=1000 else None,
            'max_dispatch_lag_ms':max(r['dispatch_lag_ms'] for r in outcomes),
            'samples':sorted(outcomes,key=lambda r:r['index'])}
    path=pathlib.Path(args.output);path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k not in ('samples','node_budgets')},indent=2))
    if any('error' in row or 'poll_http_status' in row or row.get('verdict') not in (None,'ACCEPTED') for row in outcomes): raise SystemExit('Benchmark had transport/deadline errors; inspect the report.')

if __name__=='__main__': main()
