"""Standard-library tests of independent channel/device facts, not platform mock responses."""
import os
import tempfile
import threading
import time
import unittest
import uuid
from unittest.mock import patch
from common import Fault, Store, signature
from payment import PaymentGateway
from device import DeviceGateway


class IndependentFactsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, {'CHARGEHUB_FIXED_TIME': '2026-10-18T07:00:00Z',
            'CHARGEHUB_PAYMENT_KEY': 'unit-test-only-payment-key', 'CHARGEHUB_DEVICE_KEY': 'unit-test-only-device-key'})
        self.env.start()
        self.pay = PaymentGateway(self.tmp.name + '/channel.sqlite3')
        self.device = DeviceGateway(self.tmp.name + '/device.sqlite3')
        self.pay_no = str(uuid.uuid4())
        self.request = {'outTradeNo': self.pay_no, 'amountFen': 10000, 'currency': 'CNY',
                        'expiresAt': '2026-10-18T07:10:00Z', 'notificationUrl': 'http://localhost:1/callback'}

    def tearDown(self):
        self.pay.store.conn.close()
        self.device.store.conn.close()
        self.env.stop()
        self.tmp.cleanup()

    def test_idempotent_channel_refund_and_lost_notification_keep_facts(self):
        first = self.pay.create(self.request)
        self.assertEqual(first['providerTradeNo'], self.pay.create(self.request)['providerTradeNo'])
        self.pay.store.configure({'dropNotification': True, 'dropResponseAfterCommit': True})
        self.assertTrue(self.pay.capture(self.pay_no,{})['_dropResponse'])
        refunded = self.pay.refund({'outRefundNo': str(uuid.uuid4()), 'outTradeNo': self.pay_no,
                                   'amountFen': 7900, 'currency': 'CNY'})
        again = self.pay.refund({'outRefundNo': refunded['refundNo'], 'outTradeNo': self.pay_no,
                                'amountFen': 7900, 'currency': 'CNY'})
        self.assertEqual(again['providerRefundNo'], refunded['providerRefundNo'])
        self.assertEqual(self.pay.store.conn.execute('SELECT refunded FROM payments').fetchone()[0],7900)
        with self.assertRaises(Fault) as ctx:
            self.pay.refund({'outRefundNo': str(uuid.uuid4()), 'outTradeNo': self.pay_no,
                             'amountFen': 2101, 'currency': 'CNY'})
        self.assertEqual(ctx.exception.code,'REFUND_LIMIT')
        exported = self.pay.export({'businessDate': '2026-10-18', 'billType': 'REFUND', 'timezone': 'Asia/Shanghai'})
        self.assertEqual(exported['totalCount'],1)
        self.assertIn(',7900,CNY,', exported['content'])
        self.assertEqual(self.pay.route('/mock-gateway/v1/payments/query',{'outTradeNo':self.pay_no})['status'],'SUCCESS')
        self.assertEqual(self.pay.store.conn.execute('SELECT count(*) FROM notifications WHERE delivered=0').fetchone()[0],2)

    def test_conflicting_business_parameters_and_durable_restart(self):
        first=self.pay.create(self.request)
        changed=dict(self.request,amountFen=9000)
        with self.assertRaises(Fault) as ctx:
            self.pay.create(changed)
        self.assertEqual(ctx.exception.code,'IDEMPOTENCY_CONFLICT')
        self.pay.capture(self.pay_no,{})
        restarted=PaymentGateway(self.tmp.name+'/channel.sqlite3')
        try:
            restored=restarted.route('/mock-gateway/v1/payments/query',{'outTradeNo':self.pay_no})
            self.assertEqual(restored['providerTradeNo'],first['providerTradeNo'])
            self.assertEqual(restored['status'],'SUCCESS')
        finally:
            restarted.store.conn.close()

    def test_definitive_refund_rejection_is_stable_failed_fact(self):
        self.pay.create(self.request)
        self.pay.capture(self.pay_no,{})
        self.pay.store.configure({'rejectRefund': True})
        body={'outRefundNo':str(uuid.uuid4()),'outTradeNo':self.pay_no,'amountFen':7900,'currency':'CNY'}
        rejected=self.pay.refund(body)
        self.assertEqual(rejected['status'],'FAILED')
        self.pay.store.configure({'rejectRefund': False})
        self.assertEqual(self.pay.refund(body)['providerRefundNo'],rejected['providerRefundNo'])
        self.assertEqual(self.pay.refund(body)['status'],'FAILED')
        self.assertEqual(self.pay.store.conn.execute('SELECT refunded FROM payments').fetchone()[0],0)

    def test_capture_close_race_has_single_channel_winner(self):
        self.pay.create(self.request)
        barrier=threading.Barrier(2)
        results=[]
        def execute(operation):
            barrier.wait()
            try:
                results.append(operation()['status'])
            except Fault as exc:
                results.append(exc.code)
        a=threading.Thread(target=execute,args=(lambda:self.pay.capture(self.pay_no,{}),))
        b=threading.Thread(target=execute,args=(lambda:self.pay.close(self.pay_no),))
        a.start(); b.start(); a.join(); b.join()
        final=self.pay.route('/mock-gateway/v1/payments/query',{'outTradeNo':self.pay_no})['status']
        if final=='CLOSED':
            self.assertEqual(set(results),{'CLOSED','PAYMENT_CLOSED'})
        else:
            self.assertEqual(results,['SUCCESS','SUCCESS'])
        self.assertEqual(self.pay.store.conn.execute('SELECT count(*) FROM notifications').fetchone()[0],1)

    def start(self):
        self.session=str(uuid.uuid4())
        body={'commandId':str(uuid.uuid4()),'orderNo':str(uuid.uuid4()),'sessionNo':self.session,
              'deviceId':'sim-device-01','connectorId':'1','prepaidFen':10000,
              'expiresAt':'2026-10-18T07:10:00Z','startedAt':'2026-10-18T07:00:00Z',
              'tariffSnapshot':{'timezone':'Asia/Shanghai','slots':[
                  {'startMinute':0,'endMinute':480,'energyRateFenPerKwh':50,'serviceRateFenPerKwh':30},
                  {'startMinute':480,'endMinute':1080,'energyRateFenPerKwh':80,'serviceRateFenPerKwh':50},
                  {'startMinute':1080,'endMinute':1320,'energyRateFenPerKwh':120,'serviceRateFenPerKwh':60},
                  {'startMinute':1320,'endMinute':1440,'energyRateFenPerKwh':50,'serviceRateFenPerKwh':30}]}}
        self.device.command('START',body)
        self.device.command('START',body)
        return body

    def test_demo_device_facts_stable_session_and_query_latest(self):
        body=self.start()
        final=self.device.demo(self.session)
        self.assertEqual(final['meterWh']-final['startMeterWh'],20000)
        self.assertEqual(final['rawCostMilliFen'],2600000)
        self.assertEqual(final['finalSeq'],5)
        self.assertEqual([e['seqNo'] for e in final['events']],list(range(6)))
        self.assertEqual(final['events'][-1]['occurredAt'],'2026-10-18T07:30:00Z')
        query={key:body[key] for key in ('sessionNo','deviceId','connectorId')}
        self.assertEqual(self.device.route('/mock-devices/v1/sessions/query',query)['status'],'STOPPED')
        self.assertEqual(self.device.store.conn.execute('SELECT count(*) FROM sessions').fetchone()[0],1)

    def test_boundary_samples_and_prepaid_loss_limit(self):
        self.start()
        final=self.device.advance(self.session,{'deltaWh':100000,'occurredAt':'2026-10-18T11:30:00Z'})
        self.assertEqual(final['status'],'STOPPED')
        self.assertLessEqual(final['rawCostMilliFen'],10000*1000)
        # At 18:00 Shanghai the old tariff ends and a boundary sample remains available for billing.
        self.assertTrue(any(e['sampleKind']=='TARIFF_BOUNDARY' for e in final['events']))

    def test_hmac_key_ownership_tampering_and_nonce_replay(self):
        raw=b'{"amountFen":10000}'
        now=str(int(time.time())); nonce=str(uuid.uuid4())
        headers={'X-Key-Id':'payment','X-Timestamp':now,'X-Nonce':nonce,
                 'X-Signature':signature('POST','/test',raw,now,nonce,os.environ['CHARGEHUB_PAYMENT_KEY'])}
        with self.assertRaises(Fault):
            self.pay.store.verify(headers,'/test',b'changed','payment')
        with self.assertRaises(Fault):
            self.pay.store.verify(headers,'/test',raw,'device')
        self.pay.store.verify(headers,'/test',raw,'payment')
        with self.assertRaises(Fault) as ctx:
            self.pay.store.verify(headers,'/test',raw,'payment')
        self.assertEqual(ctx.exception.code,'REPLAY')

if __name__=='__main__':
    unittest.main()
