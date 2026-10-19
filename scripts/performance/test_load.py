"""Regression checks for the isolated arrival scheduler; never makes HTTP calls."""
import importlib.util
import io
import os
from pathlib import Path
import threading
import time
import unittest
from unittest import mock


class ArrivalSchedulerTest(unittest.TestCase):
    def setUp(self):
        with mock.patch.dict(os.environ, {
            'CHARGEHUB_PERF_ISOLATED': 'yes', 'PERF_DATABASE': 'chargehub_perf_unit',
            'PERF_PASSWORD': 'unit-fixture', 'PERF_CONTROL_KEY': 'unit-fixture',
        }):
            spec = importlib.util.spec_from_file_location('load_under_test', Path(__file__).with_name('load.py'))
            self.load = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(self.load)
        self.load.OUT = io.StringIO()

    def test_late_scheduler_catches_up_when_capacity_is_available(self):
        sent = []
        original_sleep = time.sleep
        delayed = False

        def scheduler_pause(seconds):
            nonlocal delayed
            if not delayed:
                delayed = True
                original_sleep(seconds + .035)
            else:
                original_sleep(seconds)

        with mock.patch.object(self.load.time, 'sleep', side_effect=scheduler_pause):
            self.load.schedule(8, 100, lambda index, due: sent.append(index), 8)
        self.assertEqual(list(range(8)), sorted(sent))
        self.assertFalse(self.load.ROWS)

    def test_capacity_exhaustion_is_explicitly_recorded(self):
        first_worker_started = threading.Event()

        def slow_worker(index, due):
            first_worker_started.set()
            time.sleep(.1)

        self.load.schedule(5, 100, slow_worker, 1)
        self.assertTrue(first_worker_started.is_set())
        unsent = self.load.ROWS
        self.assertGreater(len(unsent), 0)
        self.assertTrue(all(row['classification'] == 'client_unsent' for row in unsent))
        self.assertTrue(all(row['reason'] == 'client_capacity' for row in unsent))
        self.assertTrue(all(row['queueMs'] >= 0 for row in unsent))

    def test_arrival_latency_preserves_queue_and_wire_time(self):
        response = mock.MagicMock()
        response.status = 200
        response.read.return_value = b'{"code":"OK","data":{}}'
        response.__enter__.return_value = response
        with mock.patch.object(self.load.request, 'urlopen', return_value=response), \
             mock.patch.object(self.load.time, 'perf_counter', side_effect=[100, 100.025]):
            row, _ = self.load.call(None, 'GET', '/v1/stations', phase='load', due=99.9)
        self.assertAlmostEqual(100, row['queueMs'])
        self.assertAlmostEqual(25, row['latencyMs'])
        self.assertAlmostEqual(125, row['arrivalLatencyMs'])

    def test_slo_uses_arrival_latency_instead_of_wire_latency(self):
        query = {'target': 'api', 'method': 'GET', 'path': '/v1/stations',
                 'classification': 'success', 'latencyMs': 10, 'arrivalLatencyMs': 310}
        create = {'target': 'api', 'method': 'POST', 'path': '/v1/charge-orders',
                  'classification': 'success', 'latencyMs': 20, 'arrivalLatencyMs': 520}
        slo = self.load.request_slo([query, create], 'mixed')
        self.assertEqual('FAIL', slo['result'])
        self.assertEqual(310, slo['querySuccessfulP95Ms'])
        self.assertEqual(520, slo['createSuccessfulP95Ms'])
        query['arrivalLatencyMs'], create['arrivalLatencyMs'] = 299, 499
        self.assertEqual('PASS', self.load.request_slo([query, create], 'mixed')['result'])


if __name__ == '__main__':
    unittest.main()
