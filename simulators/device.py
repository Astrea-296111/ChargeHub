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
            event_id TEXT NOT NULL UNIQUE, payload TEXT NOT NULL, PRIMARY KEY(session_no,seq_no));
          CREATE TABLE IF NOT EXISTS start_rejections (session_no TEXT PRIMARY KEY,device_id TEXT NOT NULL,
            connector_id TEXT NOT NULL,reason TEXT NOT NULL);
          CREATE TABLE IF NOT EXISTS meter_truth (session_no TEXT NOT NULL,seq_no INTEGER NOT NULL,
            payload TEXT NOT NULL,PRIMARY KEY(session_no,seq_no));
          CREATE TABLE IF NOT EXISTS supplementary_events (event_id TEXT PRIMARY KEY,session_no TEXT NOT NULL,
            seq_no INTEGER NOT NULL,corrects_event_id TEXT NOT NULL UNIQUE,payload TEXT NOT NULL);''')

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
        result['events'] = [json.loads(r['payload']) for r in db.execute('SELECT payload,seq_no,event_id FROM events WHERE session_no=? UNION ALL SELECT payload,seq_no,event_id FROM supplementary_events WHERE session_no=? ORDER BY seq_no,event_id', (session['sessionNo'],session['sessionNo']))]
        return result

    def event(self, db, session, kind, occurred_at, meter, sample_kind, actual_meter=None):
        session['seqNo'] += 1
        event = {'eventId': str(uuid.uuid4()), 'deviceId': session['deviceId'], 'connectorId': session['connectorId'],
                 'eventType': kind, 'sessionNo': session['sessionNo'], 'seqNo': session['seqNo'],
                 'meterWh': meter, 'occurredAt': occurred_at, 'sampleKind': sample_kind}
        if kind == 'STOPPED':
            event['finalSeq'] = session['seqNo']
            session.update(status='STOPPED', finalSeq=session['seqNo'], endedAt=occurred_at)
        session.update(meterWh=meter, occurredAt=occurred_at)
        db.execute('INSERT INTO events VALUES (?,?,?,?)', (session['sessionNo'],event['seqNo'],event['eventId'],dumps(event)))
        truth = dict(event, meterWh=meter if actual_meter is None else actual_meter)
        db.execute('INSERT INTO meter_truth VALUES (?,?,?)', (session['sessionNo'],event['seqNo'],dumps(truth)))
        session['physicalMeterWh'] = truth['meterWh']
        faults = self.store.faults()
        if not (faults.get('dropBoundarySample') and sample_kind.endswith('BOUNDARY')) and event['seqNo'] not in faults.get('dropMeterSeqNos', []):
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
            if len(parts) == 5 and parts[4] == 'stop':
                return self.stop(parts[3], body)
            if len(parts) == 5 and parts[4] == 'correct':
                return self.correct(parts[3], body)
        prefix = '/mock-devices/v1/'
        if path == prefix + 'commands/start':
            return self.command('START', body)
        if path == prefix + 'commands/stop':
            return self.command('STOP', body)
        if path == prefix + 'sessions/query':
            if self.store.faults().get('deviceQueryUnknown'):
                return {'status': 'UNKNOWN', 'sessionNo': body['sessionNo'], 'events': []}
            with self.store.tx() as db:
                session = self.session(db, body['sessionNo'])
                if not session:
                    rejected = db.execute('SELECT * FROM start_rejections WHERE session_no=?', (body['sessionNo'],)).fetchone()
                    if not rejected and body.get('expiresAt') and parse_time(self.now()) >= parse_time(body['expiresAt']):
                        db.execute('INSERT INTO start_rejections VALUES (?,?,?,?)', (body['sessionNo'],body['deviceId'],str(body['connectorId']),'START_WINDOW_CLOSED'))
                        rejected = db.execute('SELECT * FROM start_rejections WHERE session_no=?', (body['sessionNo'],)).fetchone()
                    if rejected and (rejected['device_id'] != body['deviceId'] or rejected['connector_id'] != str(body['connectorId'])):
                        raise Fault(409, 'SESSION_OWNERSHIP', 'Rejected session belongs to another connector')
                    return {'status': 'NOT_STARTED' if rejected else 'UNKNOWN', 'sessionNo': body['sessionNo'], 'events': [], 'reason': rejected['reason'] if rejected else 'START_NOT_YET_FENCED'}
                if session['deviceId'] != body['deviceId'] or session['connectorId'] != str(body['connectorId']):
                    raise Fault(409, 'SESSION_OWNERSHIP', 'Session belongs to a different device connector')
                result = self.snapshot(db, session)
                if 'fromSeq' in body or 'toSeq' in body:
                    begin,end = body.get('fromSeq',0),body.get('toSeq',session['seqNo'])
                    if type(begin) is not int or type(end) is not int or begin < 0 or end < begin or end-begin >= 10000:
                        raise Fault(400, 'INVALID_REPLAY_RANGE', 'Replay range is invalid')
                    result['events'] = [event for event in result['events'] if begin <= event['seqNo'] <= end]
                return result
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
                    # The device owns its event clock; a platform command cannot backdate metering.
                    now = self.now()
                    rejected = db.execute('SELECT reason FROM start_rejections WHERE session_no=?', (session_no,)).fetchone()
                    expired = parse_time(self.now()) >= parse_time(body['expiresAt'])
                    if rejected or expired or self.store.faults().get('deviceStartRejected'):
                        reason = rejected['reason'] if rejected else 'COMMAND_EXPIRED' if expired else 'DEVICE_REJECTED_START'
                        db.execute('INSERT OR IGNORE INTO start_rejections VALUES (?,?,?,?)', (session_no,body['deviceId'],str(body['connectorId']),reason))
                        result = {'status': 'REJECTED', 'reason': reason, 'sessionNo': session_no, 'events': []}
                        db.execute('INSERT INTO commands VALUES (?,?,?,?)', (command_id,kind,digest,dumps(result)))
                        return result
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
                    self.event(db, session, 'STOPPED', occurred_at, session.get('physicalMeterWh',session['meterWh']), 'END')
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
            original_meter = session.get('physicalMeterWh',session['meterWh'])
            duration = (end - start).total_seconds()
            for at in boundaries + [end]:
                target = original_meter + int(delta * (at - start).total_seconds() / duration)
                addition = target - session.get('physicalMeterWh',session['meterWh'])
                rate = self.rate(session, parse_time(session['occurredAt']))
                remaining = session['prepaidFen'] * 1000 - session['rawCostMilliFen']
                limit = rate > 0 and addition * rate >= remaining
                if limit:
                    addition = max(0, remaining // rate)
                    target = session.get('physicalMeterWh',session['meterWh']) + addition
                session['rawCostMilliFen'] += addition * rate
                last = at == end
                kind = 'STOPPED' if limit or (last and body.get('stop', False)) else 'METER'
                zone = ZoneInfo(session['tariffSnapshot'].get('timezone', 'Asia/Shanghai'))
                boundary_kind = 'DAY_BOUNDARY' if at.astimezone(zone).hour == 0 and at.astimezone(zone).minute == 0 else 'TARIFF_BOUNDARY'
                local = at.astimezone(zone)
                at_boundary = local.second == 0 and local.microsecond == 0 and local.hour*60+local.minute in {s['startMinute'] for s in self.slots(session)}
                sample = 'END' if kind == 'STOPPED' else (boundary_kind if at in boundaries or at_boundary else 'NORMAL')
                meter = target
                if self.store.faults().get('injectMeterRollback'):
                    meter = session['meterWh'] - 1
                self.event(db, session, kind, at.isoformat().replace('+00:00','Z'), meter, sample, actual_meter=target)
                if kind == 'STOPPED':
                    break
            return self.snapshot(db, session)

    def stop(self, session_no, body):
        """Autonomous hardware stop: no platform command is required and no extra energy is invented."""
        with self.store.tx() as db:
            session = self.session(db, session_no)
            if not session:
                raise Fault(404, 'NOT_FOUND', 'Unknown device session')
            if session['status'] != 'STOPPED':
                at = max(parse_time(body.get('occurredAt') or self.now()),parse_time(session['occurredAt']))
                self.event(db,session,'STOPPED',at.isoformat().replace('+00:00','Z'),session.get('physicalMeterWh',session['meterWh']),'END')
            return self.snapshot(db,session)

    def correct(self, session_no, body):
        """Publish an additional signed fact from simulator-held physical truth; preserve the bad original."""
        seq = body.get('seqNo')
        if type(seq) is not int or seq < 0:
            raise Fault(400,'INVALID_SEQUENCE','Correction needs an existing nonnegative sequence')
        with self.store.tx() as db:
            session = self.session(db,session_no)
            original = db.execute('SELECT * FROM events WHERE session_no=? AND seq_no=?',(session_no,seq)).fetchone()
            truth = db.execute('SELECT payload FROM meter_truth WHERE session_no=? AND seq_no=?',(session_no,seq)).fetchone()
            if not session or not original or not truth:
                raise Fault(404,'EVIDENCE_NOT_FOUND','Original and physical meter evidence are required')
            old = db.execute('SELECT payload FROM supplementary_events WHERE corrects_event_id=?',(original['event_id'],)).fetchone()
            if old:
                return {'status':'CORRECTION_PUBLISHED','event':json.loads(old['payload'])}
            payload = json.loads(truth['payload']); bad = json.loads(original['payload'])
            if all(payload[key] == bad[key] for key in ('meterWh','occurredAt','sampleKind')):
                raise Fault(409,'NO_CORRECTION_NEEDED','Stored physical truth matches the original event')
            payload['eventId'] = str(uuid.uuid4())
            db.execute('INSERT INTO supplementary_events VALUES (?,?,?,?,?)',(payload['eventId'],session_no,seq,original['event_id'],dumps(payload)))
            callback = os.environ.get('DEVICE_NOTIFICATION_URL') or os.environ.get('CHARGEHUB_CALLBACK_URL','http://127.0.0.1:8080')+'/integrations/devices/events'
            self.store.notify(db,callback,payload)
            return {'status':'CORRECTION_PUBLISHED','event':payload}

    def demo(self, session_no):
        for index in range(5):
            result = self.advance(session_no, {'deltaWh': 4000, 'stop': index == 4})
            if result['status'] == 'STOPPED':
                return result
        return result


if __name__ == '__main__':
    serve(DeviceGateway(), int(os.environ.get('DEVICE_PORT', '18082')))
