"""Independent device safety/evidence contract, no application implementation imports."""
import os
import tempfile
import threading
import unittest
import uuid
from unittest.mock import patch

from common import Fault
from device import DeviceGateway


class DeviceAcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, {'CHARGEHUB_FIXED_TIME': '2026-10-18T07:00:00Z',
            'CHARGEHUB_DEVICE_KEY': 'independent-device-test-key-12345'})
        self.env.start()
        self.device = DeviceGateway(self.temp.name + '/device.sqlite3')
        self.body = {'commandId': str(uuid.uuid4()), 'orderNo': str(uuid.uuid4()),
            'sessionNo': str(uuid.uuid4()), 'deviceId': 'physical-1', 'connectorId': '1',
            'prepaidFen': 10000, 'startedAt': '2026-10-18T07:00:00Z',
            'expiresAt': '2026-10-18T07:02:00Z',
            'tariffSnapshot': {'timezone': 'Asia/Shanghai', 'slots': [
                {'startMinute': 0, 'endMinute': 1080, 'energyRateFenPerKwh': 80, 'serviceRateFenPerKwh': 50},
                {'startMinute': 1080, 'endMinute': 1440, 'energyRateFenPerKwh': 120, 'serviceRateFenPerKwh': 60}]}}

    def tearDown(self):
        self.device.store.conn.close()
        self.env.stop()
        self.temp.cleanup()

    def query(self, **extra):
        return self.device.route('/mock-devices/v1/sessions/query',
            {**{key: self.body[key] for key in ('sessionNo', 'deviceId', 'connectorId')}, **extra})

    def test_explicit_start_rejection_is_durable_and_stable_after_fault_disabled(self):
        self.device.store.configure({'deviceStartRejected': True})
        first = self.device.command('START', self.body)
        self.assertEqual(first['status'], 'REJECTED')
        self.device.store.configure({'deviceStartRejected': False})
        self.assertEqual(self.device.command('START', self.body), first)
        second = self.device.command('START', dict(self.body, commandId=str(uuid.uuid4())))
        self.assertEqual(second['status'], 'REJECTED')
        self.assertEqual(self.query()['status'], 'NOT_STARTED')
        self.assertEqual(self.device.store.conn.execute('SELECT COUNT(*) FROM sessions').fetchone()[0], 0)

    def test_unknown_session_is_not_certified_until_deadline_and_fence_blocks_late_start(self):
        self.assertEqual(self.query()['status'], 'UNKNOWN')
        self.assertEqual(self.query(expiresAt=self.body['expiresAt'])['status'], 'UNKNOWN')
        with patch.dict(os.environ, {'CHARGEHUB_FIXED_TIME': self.body['expiresAt']}):
            self.assertEqual(self.query(expiresAt=self.body['expiresAt'])['status'], 'NOT_STARTED')
        # Rewinding the test clock cannot undo the durable no-start decision.
        self.assertEqual(self.device.command('START', self.body)['status'], 'REJECTED')
        self.assertEqual(self.device.store.conn.execute('SELECT COUNT(*) FROM sessions').fetchone()[0], 0)

    def test_expired_start_is_rejected_at_exclusive_deadline(self):
        with patch.dict(os.environ, {'CHARGEHUB_FIXED_TIME': self.body['expiresAt']}):
            self.assertEqual(self.device.command('START', self.body)['reason'], 'COMMAND_EXPIRED')
        self.assertEqual(self.query()['status'], 'NOT_STARTED')

    def test_unknown_query_never_destroys_an_actual_running_session(self):
        started = self.device.command('START', self.body)
        self.device.store.configure({'deviceQueryUnknown': True})
        self.assertEqual(self.query()['status'], 'UNKNOWN')
        self.assertEqual(self.query()['events'], [])
        self.device.store.configure({'deviceQueryUnknown': False})
        self.assertEqual(self.query()['status'], 'CHARGING')
        self.assertEqual(self.query()['events'][0]['eventId'], started['events'][0]['eventId'])

    def test_lost_stop_ack_and_duplicates_preserve_one_real_stop_and_no_invented_energy(self):
        self.device.command('START', self.body)
        self.device.store.configure({'deviceStopAckLost': True, 'dropNotification': True})
        command = {key: self.body[key] for key in ('orderNo', 'sessionNo', 'deviceId', 'connectorId')}
        command['commandId'] = str(uuid.uuid4())
        first = self.device.command('STOP', command)
        self.assertTrue(first['_dropResponse'])
        again = self.device.command('STOP', command)
        self.assertEqual(first['events'], again['events'])
        actual = self.query()
        self.assertEqual(actual['status'], 'STOPPED')
        self.assertEqual(actual['meterWh'], 100000)
        self.assertEqual(actual['finalSeq'], 1)
        self.assertEqual(actual['startedAt'], actual['endedAt'])

    def test_ordinary_advance_ending_exactly_on_boundary_keeps_required_boundary_fact(self):
        self.device.command('START', self.body)
        first = self.device.advance(self.body['sessionNo'], {'deltaWh': 12000, 'occurredAt': '2026-10-18T10:00:00Z'})
        self.assertEqual(first['events'][-1]['sampleKind'], 'TARIFF_BOUNDARY')
        final = self.device.advance(self.body['sessionNo'], {'deltaWh': 8000, 'occurredAt': '2026-10-18T11:00:00Z', 'stop': True})
        self.assertEqual(final['rawCostMilliFen'], 12000*130 + 8000*180)
        self.assertEqual([event['sampleKind'] for event in final['events']], ['START', 'TARIFF_BOUNDARY', 'END'])

    def test_meter_rollback_correction_adds_fact_without_overwriting_bad_original(self):
        self.device.command('START', self.body)
        self.device.store.configure({'injectMeterRollback': True})
        damaged = self.device.advance(self.body['sessionNo'], {'deltaWh': 4000})
        bad = damaged['events'][-1]
        self.assertEqual(bad['meterWh'], 99999)
        self.device.store.configure({'injectMeterRollback': False})
        corrected = self.device.correct(self.body['sessionNo'], {'seqNo': 1})['event']
        self.assertEqual(corrected['seqNo'], bad['seqNo'])
        self.assertNotEqual(corrected['eventId'], bad['eventId'])
        self.assertEqual(corrected['meterWh'], 104000)
        self.assertEqual(self.device.correct(self.body['sessionNo'], {'seqNo': 1})['event'], corrected)
        originals = self.device.store.conn.execute('SELECT payload FROM events WHERE session_no=? AND seq_no=1', (self.body['sessionNo'],)).fetchone()[0]
        self.assertIn('99999', originals)
        self.assertEqual(len([event for event in self.query()['events'] if event['seqNo'] == 1]), 2)
        next_sample = self.device.advance(self.body['sessionNo'], {'deltaWh': 4000})
        self.assertEqual(next_sample['meterWh'], 108000)  # Physical truth is separate from the bad notification.

    def test_range_replay_and_drop_faults_preserve_all_original_evidence(self):
        self.device.command('START', self.body)
        self.device.store.configure({'dropBoundarySample': True, 'dropMeterSeqNos': [2]})
        final = self.device.advance(self.body['sessionNo'], {'deltaWh': 20000, 'occurredAt': '2026-10-18T11:00:00Z', 'stop': True})
        self.assertEqual([event['seqNo'] for event in final['events']], [0, 1, 2])
        notifications = self.device.store.conn.execute('SELECT COUNT(*) FROM notifications').fetchone()[0]
        self.assertEqual(notifications, 1)
        selected = self.query(fromSeq=1, toSeq=1)
        self.assertEqual([event['seqNo'] for event in selected['events']], [1])
        self.assertEqual(selected['status'], 'STOPPED')

    def test_autonomous_stop_and_advance_race_have_one_contiguous_meter_chain(self):
        self.device.command('START', self.body)
        failures = []
        gate = threading.Barrier(2)
        def execute(operation):
            try:
                gate.wait(); operation()
            except Exception as exc:
                failures.append(exc)
        threads = [threading.Thread(target=execute, args=(lambda: self.device.stop(self.body['sessionNo'], {}),)),
                   threading.Thread(target=execute, args=(lambda: self.device.advance(self.body['sessionNo'], {'deltaWh': 4000}),))]
        for thread in threads: thread.start()
        for thread in threads: thread.join()
        self.assertEqual(failures, [])
        actual = self.query()
        self.assertEqual(actual['status'], 'STOPPED')
        self.assertEqual([event['seqNo'] for event in actual['events']], list(range(actual['finalSeq']+1)))
        self.assertEqual(sum(event['eventType'] == 'STOPPED' for event in actual['events']), 1)
        self.assertEqual(self.device.stop(self.body['sessionNo'], {})['events'], actual['events'])

    def test_fault_configuration_rejects_ambiguous_types_and_invalid_ranges(self):
        for body in ({'deviceStartRejected': 'false'}, {'deviceQueryUnknown': 1}, {'dropMeterSeqNos': [-1]}, {'dropMeterSeqNos': [True]}):
            with self.assertRaises(Fault):
                self.device.store.configure(body)


if __name__ == '__main__':
    unittest.main()
