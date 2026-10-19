#!/usr/bin/env python3
"""Fixed-target read-only metrics proxy. Tokens/passwords never reach Prometheus."""
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib import error, request

TARGETS = {'api': os.environ.get('MONITOR_API_URL', 'http://api:8080'),
           'worker-1': os.environ.get('MONITOR_WORKER_1_URL', 'http://chargehub-worker-1:8080'),
           'worker-2': os.environ.get('MONITOR_WORKER_2_URL', 'http://chargehub-worker-2:8080')}
USERNAME = os.environ.get('MONITOR_USERNAME', '__monitor__')
PASSWORD = Path(os.environ.get('MONITOR_PASSWORD_FILE', '/run/secrets/monitor_password')).read_text().strip()
if len(PASSWORD) < 16:
    raise RuntimeError('A dedicated monitoring password of at least 16 characters is required')
TOKENS, LOCK = {}, threading.Lock()


def fetch(target, retry=True):
    base = TARGETS[target]
    with LOCK:
        token, expires = TOKENS.get(target, ('', 0))
        if time.monotonic() >= expires:
            raw = json.dumps({'username': USERNAME, 'password': PASSWORD}).encode()
            login = request.Request(base + '/v1/auth/token', raw, {'Content-Type': 'application/json'}, method='POST')
            with request.urlopen(login, timeout=3) as response:
                value = json.loads(response.read(65536))['data']
            token = value['accessToken']
            TOKENS[target] = (token, time.monotonic() + max(1, int(value['expiresIn']) - 30))
    req = request.Request(base + '/actuator/prometheus', headers={'Authorization': 'Bearer ' + token})
    try:
        with request.urlopen(req, timeout=4) as response:
            body = response.read(8 * 1024 * 1024 + 1)
            if len(body) > 8 * 1024 * 1024:
                raise RuntimeError('Metrics response exceeds size limit')
            return body
    except error.HTTPError as exception:
        if exception.code == 401 and retry:
            with LOCK:
                TOKENS.pop(target, None)
            return fetch(target, False)
        raise


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/health':
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{"status":"UP"}')
            return
        # A caller cannot choose a URL, headers, method, or business operation.
        target = {'/metrics/api': 'api', '/metrics/worker-1': 'worker-1', '/metrics/worker-2': 'worker-2'}.get(self.path)
        if target is None:
            self.send_error(404)
            return
        try:
            body = fetch(target)
            self.send_response(200)
            self.send_header('Content-Type', 'text/plain; version=0.0.4; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except Exception as exception:
            # Do not print exception text: urllib errors may contain target details.
            print('metrics scrape failed:', target, type(exception).__name__, flush=True)
            self.send_error(503, 'Metrics target unavailable')

    def log_message(self, *_):
        pass


if __name__ == '__main__':
    ThreadingHTTPServer(('0.0.0.0', 9101), Handler).serve_forever()
