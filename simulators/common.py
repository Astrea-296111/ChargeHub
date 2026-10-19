"""Independent simulator runtime. Uses only Python's standard library."""
from __future__ import annotations
import base64
import hashlib
import hmac
import json
import os
import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib import request, error


class Fault(Exception):
    def __init__(self, status, code, message):
        self.status, self.code, self.message = status, code, message
        super().__init__(message)


def dumps(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def utc_now():
    return datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')


def parse_time(value):
    parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if parsed.tzinfo is None:
        raise ValueError('An explicit UTC offset is required')
    return parsed.astimezone(timezone.utc)


def secret(kind):
    value = os.environ.get('CHARGEHUB_' + kind.upper() + '_KEY', '')
    if len(value) < 16:
        raise RuntimeError('CHARGEHUB_' + kind.upper() + '_KEY must contain at least 16 characters')
    return value


def signature(method, path, raw, timestamp, nonce, key):
    canonical = '\n'.join([method.upper(), path.split('?')[0], str(timestamp), nonce,
                           hashlib.sha256(raw).hexdigest()])
    return base64.b64encode(hmac.new(key.encode(), canonical.encode(), hashlib.sha256).digest()).decode()


def signed_headers(method, path, raw, kind):
    timestamp, nonce = str(int(time.time())), str(uuid.uuid4())
    return {'Content-Type': 'application/json', 'X-Key-Id': kind, 'X-Timestamp': timestamp,
            'X-Nonce': nonce, 'X-Signature': signature(method, path, raw, timestamp, nonce, secret(kind))}


class Store:
    def __init__(self, filename):
        os.makedirs(os.path.dirname(os.path.abspath(filename)), exist_ok=True)
        self.conn = sqlite3.connect(filename, check_same_thread=False, isolation_level=None)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute('PRAGMA journal_mode=WAL')
        self.conn.executescript('''CREATE TABLE IF NOT EXISTS nonces (nonce TEXT PRIMARY KEY, expires INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS settings (name TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS notifications (event_id TEXT PRIMARY KEY, url TEXT NOT NULL,
              payload TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, delivered INTEGER NOT NULL DEFAULT 0,
              next_at REAL NOT NULL DEFAULT 0);''')
        self.lock = threading.RLock()

    @contextmanager
    def tx(self):
        with self.lock:
            self.conn.execute('BEGIN IMMEDIATE')
            try:
                yield self.conn
                self.conn.execute('COMMIT')
            except BaseException:
                self.conn.execute('ROLLBACK')
                raise

    def faults(self):
        with self.lock:
            row = self.conn.execute("SELECT value FROM settings WHERE name='faults'").fetchone()
            return json.loads(row['value']) if row else {}

    def configure(self, values):
        allowed = {'dropResponseAfterCommit', 'dropNotification', 'duplicateNotificationCount',
                   'notificationDelayMs', 'deviceStartTimeoutButRunning', 'deviceStopAckLost',
                   'meterReorderWindow', 'dropBoundarySample', 'injectMeterRollback',
                   'temporaryFailure', 'rejectRefund', 'randomSeed', 'deviceStartRejected',
                   'deviceQueryUnknown', 'dropMeterSeqNos', 'refundRetryableFailure',
                   'badPaymentNotificationAmountFen', 'badPaymentNotificationCurrency'}
        if set(values) - allowed:
            raise Fault(400, 'INVALID_FAULT', 'Unknown fault setting')
        # Bound developer inputs so delayed threads and duplicate sends cannot exhaust the host.
        for name in ('duplicateNotificationCount', 'meterReorderWindow'):
            if name in values and (type(values[name]) is not int or not 0 <= values[name] <= 20):
                raise Fault(400, 'INVALID_FAULT', name + ' must be 0..20')
        if 'notificationDelayMs' in values and (type(values['notificationDelayMs']) is not int or not 0 <= values['notificationDelayMs'] <= 60000):
            raise Fault(400, 'INVALID_FAULT', 'notificationDelayMs must be 0..60000')
        for name in ('dropResponseAfterCommit', 'dropNotification', 'deviceStartTimeoutButRunning',
                     'deviceStopAckLost', 'dropBoundarySample', 'injectMeterRollback', 'temporaryFailure',
                     'rejectRefund', 'deviceStartRejected', 'deviceQueryUnknown', 'refundRetryableFailure'):
            if name in values and type(values[name]) is not bool:
                raise Fault(400, 'INVALID_FAULT', name + ' must be a boolean')
        if 'dropMeterSeqNos' in values and (not isinstance(values['dropMeterSeqNos'], list)
                or len(values['dropMeterSeqNos']) > 1000
                or any(type(seq) is not int or seq < 0 for seq in values['dropMeterSeqNos'])):
            raise Fault(400, 'INVALID_FAULT', 'dropMeterSeqNos must contain at most 1000 nonnegative integers')
        if 'badPaymentNotificationAmountFen' in values and (type(values['badPaymentNotificationAmountFen']) is not int
                or not 0 <= values['badPaymentNotificationAmountFen'] <= 1000000):
            raise Fault(400, 'INVALID_FAULT', 'badPaymentNotificationAmountFen must be 0..1000000')
        if 'badPaymentNotificationCurrency' in values and (not isinstance(values['badPaymentNotificationCurrency'], str)
                or values['badPaymentNotificationCurrency'] not in ('', 'CNY', 'USD', 'EUR')):
            raise Fault(400, 'INVALID_FAULT', 'badPaymentNotificationCurrency must be a supported three-letter code or empty')
        with self.tx() as db:
            old = db.execute("SELECT value FROM settings WHERE name='faults'").fetchone()
            merged = json.loads(old['value']) if old else {}
            merged.update(values)
            db.execute("INSERT OR REPLACE INTO settings VALUES ('faults',?)", (dumps(merged),))
        return merged

    def verify(self, headers, path, raw, kind):
        if headers.get('X-Key-Id') != kind:
            raise Fault(401, 'KEY_OWNERSHIP', 'Incorrect signing identity')
        try:
            timestamp = int(headers.get('X-Timestamp', ''))
        except ValueError:
            raise Fault(401, 'INVALID_SIGNATURE', 'Missing or invalid signature timestamp')
        nonce = headers.get('X-Nonce', '')
        try:
            valid_nonce = str(uuid.UUID(nonce)) == nonce.lower()
        except ValueError:
            valid_nonce = False
        if abs(time.time() - timestamp) > 300 or not valid_nonce:
            raise Fault(401, 'INVALID_SIGNATURE', 'Expired signature or invalid nonce')
        expected = signature('POST', path, raw, timestamp, nonce, secret(kind))
        if not hmac.compare_digest(expected, headers.get('X-Signature', '')):
            raise Fault(401, 'INVALID_SIGNATURE', 'Signature rejected')
        with self.tx() as db:
            db.execute('DELETE FROM nonces WHERE expires < ?', (int(time.time()),))
            try:
                db.execute('INSERT INTO nonces VALUES (?,?)', (nonce, timestamp + 300))
            except sqlite3.IntegrityError:
                raise Fault(409, 'REPLAY', 'Signature nonce was already used')

    def notify(self, db, url, payload):
        event_id = payload.get('channelEventId', payload.get('eventId'))
        db.execute('INSERT OR IGNORE INTO notifications(event_id,url,payload,next_at) VALUES (?,?,?,?)',
                   (event_id, url, dumps(payload), time.time() + self.faults().get('notificationDelayMs', 0)/1000))

    def retry_notifications(self):
        with self.tx() as db:
            db.execute('UPDATE notifications SET attempts=0,next_at=0 WHERE delivered=0')
            count = db.execute('SELECT count(*) FROM notifications WHERE delivered=0').fetchone()[0]
        return {'pendingCount': count}

    def run_notifications(self, kind):
        while True:
            try:
                faults = self.faults()
                if not faults.get('dropNotification'):
                    with self.lock:
                        rows = self.conn.execute('SELECT * FROM notifications WHERE delivered=0 AND attempts<5 AND next_at<=? ORDER BY rowid LIMIT 20', (time.time(),)).fetchall()
                    # Reorder only transport; immutable device event sequence is not altered.
                    if faults.get('meterReorderWindow', 0) > 0:
                        rows = list(rows)
                        width = faults['meterReorderWindow']
                        rows[:width] = reversed(rows[:width])
                    for row in rows:
                        body = row['payload'].encode()
                        url = row['url']
                        from urllib.parse import urlsplit
                        path = urlsplit(url).path
                        delivered = False
                        for _ in range(1 + int(faults.get('duplicateNotificationCount', 0))):
                            try:
                                req = request.Request(url, body, signed_headers('POST', path, body, kind), method='POST')
                                with request.urlopen(req, timeout=2) as response:
                                    data = response.read(1024 * 1024 + 1)
                                    delivered = 200 <= response.status < 300 and len(data) <= 1024 * 1024
                            except (OSError, error.URLError):
                                delivered = False
                        with self.tx() as db:
                            db.execute('UPDATE notifications SET attempts=attempts+1,delivered=?,next_at=? WHERE event_id=?',
                                       (int(delivered), time.time() + min(30, 2 ** row['attempts']), row['event_id']))
            except Exception as exc:
                print('notification worker:', type(exc).__name__, flush=True)
            time.sleep(.2)


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def do_GET(self):
        if self.path == '/health':
            self.respond(200, {'code': 'OK', 'message': 'simulator', 'data': {'status': 'UP', 'simulated': True}})
        else:
            self.respond(404, {'code': 'NOT_FOUND', 'message': 'Unknown path', 'data': None})

    def do_POST(self):
        try:
            self.connection.settimeout(5)
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 <= length <= 1024 * 1024:
                raise Fault(413, 'BODY_TOO_LARGE', 'Request exceeds 1MiB')
            raw = self.rfile.read(length)
            try:
                body = json.loads(raw or b'{}')
                if not isinstance(body, dict):
                    raise ValueError()
            except (ValueError, UnicodeDecodeError):
                raise Fault(400, 'INVALID_JSON', 'Expected a JSON object')
            path = self.path.split('?')[0]
            if path.startswith('/dev/'):
                expected = secret('control')
                if not hmac.compare_digest(expected, self.headers.get('X-Control-Key', '')):
                    raise Fault(403, 'CONTROL_KEY', 'Developer control authentication required')
                if path == '/dev/faults':
                    result = self.server.app.store.configure(body)
                elif path == '/dev/notifications/retry':
                    result = self.server.app.store.retry_notifications()
                else:
                    result = self.server.app.route(path, body, True)
            else:
                self.server.app.store.verify(self.headers, path, raw, self.server.app.kind)
                result = self.server.app.route(path, body, False)
            # A deliberate lost response occurs only after the route committed durable facts.
            drop = isinstance(result, dict) and result.pop('_dropResponse', False)
            if drop:
                self.close_connection = True
                self.connection.close()
                return
            self.respond(200, {'code': 'OK', 'message': 'success', 'data': result})
        except Fault as exc:
            self.respond(exc.status, {'code': exc.code, 'message': exc.message, 'data': None})
        except (KeyError, ValueError, TypeError):
            self.respond(400, {'code': 'INVALID_REQUEST', 'message': 'Missing or invalid fields', 'data': None})
        except Exception as exc:
            print('request failure:', type(exc).__name__, flush=True)
            self.respond(500, {'code': 'INTERNAL_ERROR', 'message': 'Simulator internal error', 'data': None})

    def respond(self, status, payload):
        payload['requestId'] = str(uuid.uuid4())
        raw = dumps(payload).encode()
        try:
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, fmt, *args):
        print(self.server.app.kind, fmt % args, flush=True)


def serve(app, port):
    secret(app.kind)
    secret('control')
    server = ThreadingHTTPServer((os.environ.get('SIMULATOR_HOST', '127.0.0.1'), port), Handler)
    server.app = app
    threading.Thread(target=app.store.run_notifications, args=(app.kind,), daemon=True).start()
    print(app.kind, 'simulator listening on', port, flush=True)
    server.serve_forever()
