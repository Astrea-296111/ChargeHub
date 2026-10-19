"""ChargeHub mock gateway: independent, durable payment and refund facts."""
from __future__ import annotations
import csv
import hashlib
import io
import json
import os
import uuid
from datetime import date
from zoneinfo import ZoneInfo
from common import Store, Fault, dumps, parse_time, utc_now, serve

HEADER = ['channel','bill_type','business_date','source_timezone','provider_biz_no',
          'out_trade_no','out_refund_no','amount_fen','currency','occurred_at','status']


class PaymentGateway:
    kind = 'payment'
    def __init__(self, filename=None):
        self.store = Store(filename or os.environ.get('PAYMENT_DB', '/tmp/chargehub/payment.sqlite3'))
        self.store.conn.executescript('''
          CREATE TABLE IF NOT EXISTS payments (out_trade_no TEXT PRIMARY KEY, provider_no TEXT UNIQUE NOT NULL,
            amount INTEGER NOT NULL CHECK(amount>0), currency TEXT NOT NULL, status TEXT NOT NULL,
            expires_at TEXT NOT NULL, occurred_at TEXT, notification_url TEXT NOT NULL, request_hash TEXT NOT NULL,
            event_id TEXT, refunded INTEGER NOT NULL DEFAULT 0 CHECK(refunded>=0 AND refunded<=amount));
          CREATE TABLE IF NOT EXISTS refunds (out_refund_no TEXT PRIMARY KEY, out_trade_no TEXT NOT NULL,
            provider_no TEXT UNIQUE NOT NULL, amount INTEGER NOT NULL CHECK(amount>0), currency TEXT NOT NULL,
            status TEXT NOT NULL, occurred_at TEXT NOT NULL, event_id TEXT NOT NULL);
        ''')

        columns = {r['name'] for r in self.store.conn.execute('PRAGMA table_info(refunds)')}
        for name in ('retryable', 'attempt'):
            if name not in columns:
                self.store.conn.execute('ALTER TABLE refunds ADD COLUMN ' + name + ' INTEGER NOT NULL DEFAULT 0')

    def now(self):
        return os.environ.get('CHARGEHUB_FIXED_TIME') or utc_now()

    def payment_view(self, row):
        return {'outTradeNo': row['out_trade_no'], 'payNo': row['out_trade_no'],
                'providerTradeNo': row['provider_no'], 'providerBizNo': row['provider_no'],
                'amountFen': row['amount'], 'currency': row['currency'], 'status': row['status'],
                'occurredAt': row['occurred_at'], 'expiresAt': row['expires_at'],
                'channelEventId': row['event_id'],
                'checkoutUrl': '/dev/payments/' + row['out_trade_no'] + '/capture'}

    def refund_view(self, row):
        return {'outRefundNo': row['out_refund_no'], 'refundNo': row['out_refund_no'],
                'outTradeNo': row['out_trade_no'], 'payNo': row['out_trade_no'],
                'providerRefundNo': row['provider_no'], 'providerBizNo': row['provider_no'],
                'amountFen': row['amount'], 'currency': row['currency'], 'status': row['status'],
                'occurredAt': row['occurred_at'], 'channelEventId': row['event_id'],
                'retryable': bool(row['retryable']), 'attempt': row['attempt']}

    def payment(self, db, pay_no):
        row = db.execute('SELECT * FROM payments WHERE out_trade_no=?', (pay_no,)).fetchone()
        if row is None:
            raise Fault(404, 'NOT_FOUND', 'Unknown channel payment')
        return row

    def route(self, path, body, dev=False):
        if dev and path == '/dev/state':
            with self.store.lock:
                return {'payments': [self.payment_view(r) for r in self.store.conn.execute('SELECT * FROM payments ORDER BY rowid')],
                        'refunds': [self.refund_view(r) for r in self.store.conn.execute('SELECT * FROM refunds ORDER BY rowid')],
                        'notifications': [dict(r) for r in self.store.conn.execute('SELECT event_id,attempts,delivered FROM notifications ORDER BY rowid')]}
        if dev and path.startswith('/dev/payments/') and path.endswith('/capture'):
            return self.capture(path.split('/')[3], body)
        prefix = '/mock-gateway/v1/'
        if path == prefix + 'payments':
            return self.create(body)
        if path == prefix + 'payments/query':
            with self.store.lock:
                return self.payment_view(self.payment(self.store.conn, body['outTradeNo']))
        if path == prefix + 'payments/close':
            return self.close(body['outTradeNo'])
        if path == prefix + 'refunds':
            return self.refund(body)
        if path == prefix + 'refunds/retry':
            return self.refund(body, retry=True)
        if path == prefix + 'refunds/query':
            with self.store.lock:
                row = self.store.conn.execute('SELECT * FROM refunds WHERE out_refund_no=?', (body['outRefundNo'],)).fetchone()
                if not row:
                    return {'status': 'NOT_FOUND', 'outRefundNo': body['outRefundNo']}
                return self.refund_view(row)
        if path == prefix + 'bills/export':
            return self.export(body)
        raise Fault(404, 'NOT_FOUND', 'Unknown path')

    def create(self, body):
        pay_no = str(uuid.UUID(body['outTradeNo']))
        amount = body['amountFen']
        if type(amount) is not int or amount <= 0 or body.get('currency') != 'CNY':
            raise Fault(400, 'INVALID_AMOUNT', 'Positive integer CNY amount required')
        parse_time(body['expiresAt'])
        callback = body.get('notificationUrl') or os.environ.get('PAYMENT_NOTIFICATION_URL') or os.environ.get('CHARGEHUB_CALLBACK_URL', 'http://127.0.0.1:8080') + '/integrations/payments/mock/notifications'
        # Only the authenticated platform supplies callback destinations.
        digest = hashlib.sha256(dumps(body).encode()).hexdigest()
        with self.store.tx() as db:
            old = db.execute('SELECT * FROM payments WHERE out_trade_no=?', (pay_no,)).fetchone()
            if old:
                if old['request_hash'] != digest:
                    raise Fault(409, 'IDEMPOTENCY_CONFLICT', 'Payment identifier has different parameters')
                return self.payment_view(old)
            db.execute('INSERT INTO payments(out_trade_no,provider_no,amount,currency,status,expires_at,notification_url,request_hash) VALUES (?,?,?,?,?,?,?,?)',
                       (pay_no, 'mock-pay-' + str(uuid.uuid4()), amount, 'CNY', 'PENDING', body['expiresAt'], callback, digest))
            row = self.payment(self.store.conn, pay_no)
            return self.payment_view(row)

    def capture(self, pay_no, body):
        with self.store.tx() as db:
            row = self.payment(db, pay_no)
            if row['status'] == 'CLOSED':
                raise Fault(409, 'PAYMENT_CLOSED', 'Closed payment cannot be captured')
            if row['status'] == 'PENDING':
                now = body.get('occurredAt') or self.now()
                if parse_time(now) > parse_time(row['expires_at']):
                    raise Fault(409, 'PAYMENT_EXPIRED', 'Payment deadline has passed')
                event_id = str(uuid.uuid4())
                db.execute("UPDATE payments SET status='SUCCESS',occurred_at=?,event_id=? WHERE out_trade_no=?", (now,event_id,pay_no))
                event = {'channelEventId': event_id, 'type': 'PAYMENT_SUCCESS', 'payNo': pay_no,
                         'providerBizNo': row['provider_no'], 'amountFen': row['amount'], 'currency': row['currency'], 'occurredAt': now}
                faults = self.store.faults()
                if faults.get('badPaymentNotificationAmountFen', 0) > 0:
                    event['amountFen'] = faults['badPaymentNotificationAmountFen']
                if faults.get('badPaymentNotificationCurrency', ''):
                    event['currency'] = faults['badPaymentNotificationCurrency']
                self.store.notify(db, row['notification_url'], event)
            result = self.payment_view(self.payment(db, pay_no))
            result['_dropResponse'] = bool(self.store.faults().get('dropResponseAfterCommit'))
            return result

    def close(self, pay_no):
        with self.store.tx() as db:
            row = self.payment(db, pay_no)
            if row['status'] == 'PENDING':
                now, event_id = self.now(), str(uuid.uuid4())
                db.execute("UPDATE payments SET status='CLOSED',occurred_at=?,event_id=? WHERE out_trade_no=?", (now,event_id,pay_no))
                self.store.notify(db, row['notification_url'], {'channelEventId': event_id, 'type': 'PAYMENT_CLOSED',
                    'payNo': pay_no, 'providerBizNo': row['provider_no'], 'amountFen': row['amount'], 'currency': row['currency'], 'occurredAt': now})
            return self.payment_view(self.payment(db, pay_no))

    def refund(self, body, retry=False):
        refund_no, pay_no = str(uuid.UUID(body['outRefundNo'])), body['outTradeNo']
        amount = body['amountFen']
        if type(amount) is not int or amount <= 0 or body.get('currency') != 'CNY':
            raise Fault(400, 'INVALID_AMOUNT', 'Positive integer CNY amount required')
        attempt = body.get('attempt', 0)
        if type(attempt) is not int or attempt < 0:
            raise Fault(400, 'INVALID_ATTEMPT', 'Attempt must be a nonnegative integer')
        with self.store.tx() as db:
            old = db.execute('SELECT * FROM refunds WHERE out_refund_no=?', (refund_no,)).fetchone()
            if old:
                if old['out_trade_no'] != pay_no or old['amount'] != amount or old['currency'] != body['currency']:
                    raise Fault(409, 'IDEMPOTENCY_CONFLICT', 'Refund identifier has different parameters')
                if not retry or attempt <= old['attempt'] or old['status'] == 'SUCCESS':
                    return self.refund_view(old)
                if old['status'] != 'FAILED' or not old['retryable']:
                    raise Fault(409, 'REFUND_FINAL', 'Final channel refusal cannot be retried')
                if attempt != old['attempt'] + 1:
                    raise Fault(409, 'ATTEMPT_CONFLICT', 'Retry must use the next original-refund attempt')
            elif retry:
                raise Fault(404, 'NOT_FOUND', 'Original channel refund does not exist')
            faults = self.store.faults()
            if faults.get('temporaryFailure'):
                raise Fault(503, 'TEMPORARILY_UNAVAILABLE', 'Injected temporary channel failure')
            payment = self.payment(db, pay_no)
            if payment['status'] != 'SUCCESS' or payment['refunded'] + amount > payment['amount']:
                raise Fault(409, 'REFUND_LIMIT', 'Refund exceeds available captured channel amount')
            now, event_id = self.now(), str(uuid.uuid4())
            provider = old['provider_no'] if old else 'mock-refund-' + str(uuid.uuid4())
            status = 'FAILED' if faults.get('rejectRefund') or faults.get('refundRetryableFailure') else 'SUCCESS'
            retryable = status == 'FAILED' and bool(faults.get('refundRetryableFailure')) and not faults.get('rejectRefund')
            if old:
                db.execute('UPDATE refunds SET status=?,occurred_at=?,event_id=?,retryable=?,attempt=? WHERE out_refund_no=?',
                           (status,now,event_id,int(retryable),attempt,refund_no))
            else:
                db.execute('INSERT INTO refunds(out_refund_no,out_trade_no,provider_no,amount,currency,status,occurred_at,event_id,retryable,attempt) VALUES (?,?,?,?,?,?,?,?,?,?)',
                           (refund_no,pay_no,provider,amount,'CNY',status,now,event_id,int(retryable),attempt))
            if status == 'SUCCESS':
                db.execute('UPDATE payments SET refunded=refunded+? WHERE out_trade_no=?', (amount,pay_no))
            self.store.notify(db, payment['notification_url'], {'channelEventId': event_id, 'type': 'REFUND_' + status,
                'payNo': pay_no, 'refundNo': refund_no, 'providerBizNo': provider, 'amountFen': amount,
                'currency': 'CNY', 'occurredAt': now, 'retryable': retryable, 'attempt': attempt})
            result = self.refund_view(db.execute('SELECT * FROM refunds WHERE out_refund_no=?', (refund_no,)).fetchone())
            result['_dropResponse'] = bool(faults.get('dropResponseAfterCommit'))
            return result

    def export(self, body):
        business_date = date.fromisoformat(body['businessDate'])
        kind = body['billType']
        if kind not in ('PAYMENT', 'REFUND'):
            raise Fault(400, 'INVALID_BILL_TYPE', 'Expected PAYMENT or REFUND')
        try:
            zone = ZoneInfo(body.get('timezone', 'Asia/Shanghai'))
        except Exception:
            raise Fault(400, 'INVALID_TIMEZONE', 'Unknown source timezone')
        output = io.StringIO(newline='')
        writer = csv.writer(output, lineterminator='\n')
        writer.writerow(HEADER)
        with self.store.lock:
            rows = self.store.conn.execute('SELECT * FROM ' + ('payments' if kind == 'PAYMENT' else 'refunds') + " WHERE status='SUCCESS' ORDER BY occurred_at,provider_no").fetchall()
            count = 0
            for row in rows:
                if parse_time(row['occurred_at']).astimezone(zone).date() != business_date:
                    continue
                writer.writerow(['MOCK',kind,str(business_date),str(zone),row['provider_no'],row['out_trade_no'],
                    row['out_refund_no'] if kind == 'REFUND' else '',row['amount'],row['currency'],row['occurred_at'],'SUCCESS'])
                count += 1
        content = output.getvalue()
        if len(content.encode()) > 10 * 1024 * 1024:
            raise Fault(413, 'BILL_TOO_LARGE', 'Split channel bill by business date')
        return {'channel': 'MOCK', 'billType': kind, 'businessDate': str(business_date), 'sourceTimezone': str(zone),
                'content': content, 'fileHash': hashlib.sha256(content.encode()).hexdigest(), 'totalCount': count, 'complete': True}


if __name__ == '__main__':
    serve(PaymentGateway(), int(os.environ.get('PAYMENT_PORT', '18081')))
