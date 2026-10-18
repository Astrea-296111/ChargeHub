"""Independent charging-device simulator with immutable sequence-bearing events."""
from __future__ import annotations
import hashlib
import json
import os
import uuid
from datetime import timedelta
from zoneinfo import ZoneInfo
from common import Store, Fault, dumps, parse_time, utc_now, serve


class DeviceGateway:
    kind = 'device'
    def __init__(self, filename=None):
        self.store = Store(filename or os.environ.get('DEVICE_DB', '/tmp/chargehub/device.sqlite3'))
        self.store.conn.executescript('''CREATE TABLE IF NOT EXISTS sessions (
            session_no TEXT PRIMARY KEY, order_no TEXT NOT NULL UNIQUE, device_id TEXT NOT NULL,
            connector_id TEXT NOT NULL, status TEXT NOT NULL, state TEXT NOT NULL);
          CREATE UNIQUE INDEX IF NOT EXISTS active_connector ON sessions(device_id,connector_id) WHERE status='CHARGING';
          CREATE TABLE IF NOT EXISTS commands (command_id TEXT PRIMARY KEY, kind TEXT NOT NULL,
            request_hash TEXT NOT NULL, result TEXT NOT NULL);
          CREATE TABLE IF NOT EXISTS events (session_no TEXT NOT NULL, seq_no INTEGER NOT NULL,
            event_id TEXT NOT NULL UNIQUE, payload TEXT NOT NULL, PRIMARY KEY(session_no,seq_no));''')

    def now(self):
        return os.environ.get('CHARGEHUB_FIXED_TIME') or utc_now()

    def session(self, db, session_no):
        row = db.execute('SELECT * FROM sessions WHERE session_no=?', (session_no,)).fetchone()
        return json.loads(row['state']) if row else None

    def save(self, db, session):
        db.execute('UPDATE sessions SET status=?,state=? WHERE session_no=?',
                   (session['status'], dumps(session), session['sessionNo']))

    def snapshot(self, db, session):
        result = dict(session)
        result.pop('tariffSnapshot', None)
        result['events'] = [json.loads(r['payload']) for r in db.execute('SELECT payload FROM events WHERE session_no=? ORDER BY seq_no', (session['sessionNo'],))]
        return result

    def event(self, db, session, kind, occurred_at, meter, sample_kind):
        session['seqNo'] += 1
        event = {'eventId': str(uuid.uuid4()), 'deviceId': session['deviceId'], 'connectorId': session['connectorId'],
                 'eventType': kind, 'sessionNo': session['sessionNo'], 'seqNo': session['seqNo'],
                 'meterWh': meter, 'occurredAt': occurred_at, 'sampleKind': sample_kind}
        if kind == 'STOPPED':
            event['finalSeq'] = session['seqNo']
            session.update(status='STOPPED', finalSeq=session['seqNo'], endedAt=occurred_at)
        session.update(meterWh=meter, occurredAt=occurred_at)
        db.execute('INSERT INTO events VALUES (?,?,?,?)', (session['sessionNo'],event['seqNo'],event['eventId'],dumps(event)))
        faults = self.store.faults()
        if not (faults.get('dropBoundarySample') and sample_kind.endswith('BOUNDARY')):
            callback = os.environ.get('DEVICE_NOTIFICATION_URL') or os.environ.get('CHARGEHUB_CALLBACK_URL', 'http://127.0.0.1:8080') + '/integrations/devices/events'
            self.store.notify(db, callback, event)
        self.save(db, session)
        return event

    def route(self, path, body, dev=False):
        if dev and path == '/dev/state':
            with self.store.lock:
                return {'sessions': [self.snapshot(self.store.conn,json.loads(r['state'])) for r in self.store.conn.execute('SELECT state FROM sessions ORDER BY rowid')],
                        'notifications': [dict(r) for r in self.store.conn.execute('SELECT event_id,attempts,delivered FROM notifications ORDER BY rowid')]}
        if dev and path.startswith('/dev/sessions/'):
            parts = path.split('/')
            if len(parts) == 5 and parts[4] == 'advance':
                return self.advance(parts[3], body)
            if len(parts) == 5 and parts[4] == 'demo':
                return self.demo(parts[3])
        prefix = '/mock-devices/v1/'
        if path == prefix + 'commands/start':
            return self.command('START', body)
        if path == prefix + 'commands/stop':
            return self.command('STOP', body)
        if path == prefix + 'sessions/query':
            with self.store.lock:
                session = self.session(self.store.conn, body['sessionNo'])
                if not session:
                    return {'status': 'NOT_STARTED', 'sessionNo': body['sessionNo'], 'events': []}
                if session['deviceId'] != body['deviceId'] or session['connectorId'] != str(body['connectorId']):
                    raise Fault(409, 'SESSION_OWNERSHIP', 'Session belongs to a different device connector')
                return self.snapshot(self.store.conn, session)
        raise Fault(404, 'NOT_FOUND', 'Unknown path')

    def command(self, kind, body):
        command_id = str(uuid.UUID(body['commandId']))
        digest = hashlib.sha256(dumps(body).encode()).hexdigest()
        with self.store.tx() as db:
            old = db.execute('SELECT * FROM commands WHERE command_id=?', (command_id,)).fetchone()
            if old:
                if old['kind'] != kind or old['request_hash'] != digest:
                    raise Fault(409, 'COMMAND_CONFLICT', 'Stable command identifier has different parameters')
                return json.loads(old['result'])
            session_no = str(uuid.UUID(body['sessionNo']))
            session = self.session(db, session_no)
            if kind == 'START':
                if session:
                    if session['orderNo'] != body['orderNo'] or session['deviceId'] != body['deviceId'] or session['connectorId'] != str(body['connectorId']):
                        raise Fault(409, 'SESSION_CONFLICT', 'Session identifier already belongs to another order or connector')
                else:
                    now = body.get('startedAt') or self.now()
                    if parse_time(self.now()) > parse_time(body['expiresAt']):
                        raise Fault(422, 'COMMAND_EXPIRED', 'Start command expired')
                    occupied = db.execute("SELECT session_no FROM sessions WHERE device_id=? AND connector_id=? AND status='CHARGING'", (body['deviceId'],str(body['connectorId']))).fetchone()
                    if occupied:
                        raise Fault(409, 'CONNECTOR_OCCUPIED', 'Connector is already running another session')
                    if type(body['prepaidFen']) is not int or body['prepaidFen'] <= 0:
                        raise Fault(400, 'INVALID_PREPAYMENT', 'Positive integer prepayment required')
                    snapshot = body['tariffSnapshot']
                    if isinstance(snapshot, str):
                        snapshot = json.loads(snapshot)
                    session = {'sessionNo': session_no, 'orderNo': body['orderNo'], 'deviceId': body['deviceId'],
                               'connectorId': str(body['connectorId']), 'status': 'CHARGING', 'seqNo': -1,
                               'startMeterWh': 100000, 'meterWh': 100000, 'startedAt': now, 'occurredAt': now,
                               'prepaidFen': body['prepaidFen'], 'rawCostMilliFen': 0, 'tariffSnapshot': snapshot}
                    db.execute('INSERT INTO sessions VALUES (?,?,?,?,?,?)', (session_no,body['orderNo'],body['deviceId'],str(body['connectorId']),'CHARGING',dumps(session)))
                    self.event(db, session, 'STARTED', now, 100000, 'START')
            else:
                if not session:
                    raise Fault(404, 'NOT_STARTED', 'Unknown device session')
                if session['deviceId'] != body['deviceId'] or session['connectorId'] != str(body['connectorId']):
                    raise Fault(409, 'SESSION_OWNERSHIP', 'Session belongs to a different device connector')
                if session['status'] == 'CHARGING':
                    occurred_at = body.get('occurredAt') or self.now()
                    if parse_time(occurred_at) < parse_time(session['occurredAt']):
                        occurred_at = session['occurredAt']
                    # Device-clock stop acknowledgement never invents additional energy.
                    self.event(db, session, 'STOPPED', occurred_at, session['meterWh'], 'END')
            result = self.snapshot(db, session)
            db.execute('INSERT INTO commands VALUES (?,?,?,?)', (command_id,kind,digest,dumps(result)))
            faults = self.store.faults()
            result['_dropResponse'] = bool((kind == 'START' and faults.get('deviceStartTimeoutButRunning')) or (kind == 'STOP' and faults.get('deviceStopAckLost')))
            return result

    def slots(self, session):
        snapshot = session['tariffSnapshot']
        if isinstance(snapshot, list):
            return snapshot
        return snapshot.get('slots', snapshot.get('tariffSlots', []))

    def rate(self, session, at):
        snapshot = session['tariffSnapshot']
        zone = ZoneInfo(snapshot.get('timezone', 'Asia/Shanghai') if isinstance(snapshot, dict) else 'Asia/Shanghai')
        local = at.astimezone(zone)
        minute = local.hour * 60 + local.minute
        for slot in self.slots(session):
            start = slot.get('startMinute', slot.get('start_minute'))
            end = slot.get('endMinute', slot.get('end_minute'))
            if start <= minute < end:
                return slot.get('energyRateFenPerKwh', slot.get('energy_rate_fen_per_kwh', 0)) + slot.get('serviceRateFenPerKwh', slot.get('service_rate_fen_per_kwh', 0))
        raise Fault(422, 'TARIFF_GAP', 'Tariff snapshot does not cover device clock')

    def boundaries(self, session, start, end):
        snapshot = session['tariffSnapshot']
        zone = ZoneInfo(snapshot.get('timezone', 'Asia/Shanghai') if isinstance(snapshot, dict) else 'Asia/Shanghai')
        minutes = {int(s.get('startMinute', s.get('start_minute'))) for s in self.slots(session)}
        # UTC minute stepping handles local date rollover and timezone transitions consistently.
        cursor = start.replace(second=0, microsecond=0) + timedelta(minutes=1)
        result = []
        while cursor < end:
            local = cursor.astimezone(zone)
            if local.hour * 60 + local.minute in minutes:
                result.append(cursor)
            cursor += timedelta(minutes=1)
        return result

    def advance(self, session_no, body):
        delta = body.get('deltaWh', 4000)
        if type(delta) is not int or delta < 0:
            raise Fault(400, 'INVALID_METER', 'deltaWh must be a nonnegative integer')
        with self.store.tx() as db:
            session = self.session(db, session_no)
            if not session:
                raise Fault(404, 'NOT_FOUND', 'Unknown device session')
            if session['status'] == 'STOPPED':
                return self.snapshot(db, session)
            start = parse_time(session['occurredAt'])
            end = parse_time(body['occurredAt']) if body.get('occurredAt') else start + timedelta(minutes=6)
            if end <= start or end - start > timedelta(days=7):
                raise Fault(422, 'INVALID_CLOCK', 'Next sample must advance the clock by at most 7 days')
            boundaries = self.boundaries(session, start, end)
            original_meter = session['meterWh']
            duration = (end - start).total_seconds()
            for at in boundaries + [end]:
                target = original_meter + int(delta * (at - start).total_seconds() / duration)
                addition = target - session['meterWh']
                rate = self.rate(session, parse_time(session['occurredAt']))
                remaining = session['prepaidFen'] * 1000 - session['rawCostMilliFen']
                limit = rate > 0 and addition * rate >= remaining
                if limit:
                    addition = max(0, remaining // rate)
                    target = session['meterWh'] + addition
                session['rawCostMilliFen'] += addition * rate
                last = at == end
                kind = 'STOPPED' if limit or (last and body.get('stop', False)) else 'METER'
                zone = ZoneInfo(session['tariffSnapshot'].get('timezone', 'Asia/Shanghai'))
                boundary_kind = 'DAY_BOUNDARY' if at.astimezone(zone).hour == 0 and at.astimezone(zone).minute == 0 else 'TARIFF_BOUNDARY'
                sample = 'END' if kind == 'STOPPED' else (boundary_kind if at in boundaries else 'NORMAL')
                meter = target
                if self.store.faults().get('injectMeterRollback'):
                    meter = session['meterWh'] - 1
                self.event(db, session, kind, at.isoformat().replace('+00:00','Z'), meter, sample)
                if kind == 'STOPPED':
                    break
            return self.snapshot(db, session)

    def demo(self, session_no):
        for index in range(5):
            result = self.advance(session_no, {'deltaWh': 4000, 'stop': index == 4})
            if result['status'] == 'STOPPED':
                return result
        return result


if __name__ == '__main__':
    serve(DeviceGateway(), int(os.environ.get('DEVICE_PORT', '18082')))
