"""Controlled channel failures retain independent facts and original business identifiers."""
import json
import tempfile
import unittest
import uuid
from unittest.mock import patch
from common import Fault
from payment import PaymentGateway

class PaymentFaultTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.env=patch.dict('os.environ',{'CHARGEHUB_FIXED_TIME':'2026-10-18T07:00:00Z'});self.env.start()
        self.gateway=PaymentGateway(self.tmp.name+'/independent.sqlite3')
        self.pay=str(uuid.uuid4())
        self.gateway.create({'outTradeNo':self.pay,'amountFen':10000,'currency':'CNY','expiresAt':'2026-10-18T07:05:00Z','notificationUrl':'http://127.0.0.1:1/callback'})
    def tearDown(self):
        self.gateway.store.conn.close();self.env.stop();self.tmp.cleanup()
    def refund(self,no,attempt=0,retry=False):
        return self.gateway.refund({'outTradeNo':self.pay,'outRefundNo':no,'amountFen':7900,'currency':'CNY','attempt':attempt},retry=retry)
    def test_retryable_failure_uses_original_number_and_one_channel_cash_out(self):
        self.gateway.capture(self.pay,{})
        no=str(uuid.uuid4());self.gateway.store.configure({'refundRetryableFailure':True})
        failed=self.refund(no);self.assertEqual(failed['status'],'FAILED');self.assertTrue(failed['retryable'])
        self.gateway.store.configure({'refundRetryableFailure':False})
        success=self.refund(no,1,True);self.assertEqual(success['status'],'SUCCESS');self.assertEqual(success['outRefundNo'],no)
        self.assertEqual(success['providerRefundNo'],failed['providerRefundNo'])
        for _ in range(20):self.assertEqual(self.refund(no,1,True)['status'],'SUCCESS')
        row=self.gateway.store.conn.execute('SELECT refunded FROM payments').fetchone();self.assertEqual(row[0],7900)
        self.assertEqual(self.gateway.store.conn.execute('SELECT count(*) FROM refunds').fetchone()[0],1)
    def test_final_rejection_cannot_be_retried_under_same_or_new_attempt(self):
        self.gateway.capture(self.pay,{})
        no=str(uuid.uuid4());self.gateway.store.configure({'rejectRefund':True});failed=self.refund(no)
        self.assertFalse(failed['retryable']);self.gateway.store.configure({'rejectRefund':False})
        with self.assertRaises(Fault) as error:self.refund(no,1,True)
        self.assertEqual(error.exception.code,'REFUND_FINAL')
        self.assertEqual(self.gateway.store.conn.execute('SELECT refunded FROM payments').fetchone()[0],0)
    def test_bad_notification_does_not_corrupt_channel_payment_or_export(self):
        self.gateway.store.configure({'badPaymentNotificationAmountFen':9999,'badPaymentNotificationCurrency':'USD'})
        self.gateway.capture(self.pay,{})
        notification=json.loads(self.gateway.store.conn.execute('SELECT payload FROM notifications').fetchone()[0])
        self.assertEqual(notification['amountFen'],9999);self.assertEqual(notification['currency'],'USD')
        fact=self.gateway.route('/mock-gateway/v1/payments/query',{'outTradeNo':self.pay})
        self.assertEqual(fact['amountFen'],10000);self.assertEqual(fact['currency'],'CNY')
        bill=self.gateway.export({'businessDate':'2026-10-18','billType':'PAYMENT','timezone':'Asia/Shanghai'})
        self.assertIn(',10000,CNY,',bill['content'])
    def test_lost_refund_response_and_notification_still_query_same_success(self):
        self.gateway.capture(self.pay,{})
        self.gateway.store.configure({'dropNotification':True,'dropResponseAfterCommit':True})
        no=str(uuid.uuid4());result=self.refund(no);self.assertTrue(result['_dropResponse'])
        recovered=self.gateway.route('/mock-gateway/v1/refunds/query',{'outRefundNo':no})
        self.assertEqual(recovered['status'],'SUCCESS');self.assertEqual(recovered['amountFen'],7900)
        self.assertEqual(self.gateway.store.conn.execute('SELECT count(*) FROM refunds').fetchone()[0],1)

if __name__=='__main__':unittest.main()
