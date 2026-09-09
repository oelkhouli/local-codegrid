"""Standard-library client shared by the local smoke and benchmark commands."""
import http.cookiejar
import json
import pathlib
import time
import urllib.error
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]

def environment(path=None):
    return dict(line.split('=', 1) for line in pathlib.Path(path or ROOT / '.env').read_text().splitlines() if '=' in line and not line.startswith('#'))

class Client:
    def __init__(self, base='http://127.0.0.1:8080'):
        self.base = base.rstrip('/')
        self.csrf = ''
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(self, path, body=None, headers=None):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base + path, data=data, headers={'Content-Type': 'application/json', 'Origin': self.base, 'X-CSRF-Token': self.csrf, **(headers or {})})
        with self.http.open(request, timeout=20) as response:
            return json.load(response)

    def login(self, username, password):
        result = self.request('/api/auth/login', {'username': username, 'password': password})
        self.csrf = result['csrf']
        return result

    def submit(self, language, source, stdin='', expected='', mode='RUN', key=None):
        key = key or str(uuid.uuid4())
        body = {'language': language, 'source': source, 'tests': [{'input': stdin, 'expected': expected}], 'mode': mode}
        for attempt in range(20):
            try:
                return self.request('/api/jobs', body, {'Idempotency-Key': key})['id']
            except urllib.error.HTTPError as error:
                if error.code != 429 or attempt == 19:
                    raise
                time.sleep(1)

    def wait(self, job, timeout=180):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            result = self.request('/api/jobs/' + job)
            if result['state'] in ('FINISHED', 'CANCELLED'):
                return result
            time.sleep(0.4)
        raise TimeoutError('Job did not finish: ' + job)
