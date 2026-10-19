#!/usr/bin/env python3
"""Read-only end-to-end monitoring gate; never records credentials or raw metrics."""
from __future__ import annotations
import argparse
import base64
import json
import math
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib import request, parse

ROOT = Path(__file__).resolve().parents[2]
EXPECTED_TARGETS = {('api', None), ('worker', '1'), ('worker', '2')}


def fetch(url, authorization=None):
    headers = {'Accept': 'application/json'}
    if authorization:
        headers['Authorization'] = authorization
    with request.urlopen(request.Request(url, headers=headers), timeout=5) as response:
        return json.load(response)


def application_series(values, check, *, require_up=False):
    """Require real finite samples from each application replica, without persisting values."""
    counts = {}
    for sample in values:
        labels = sample.get('metric', {})
        target = (labels.get('component'), labels.get('replica'))
        try:
            value = float(sample['value'][1])
        except (KeyError, IndexError, TypeError, ValueError):
            raise ValueError(check) from None
        if target not in EXPECTED_TARGETS or not math.isfinite(value) or require_up and value != 1:
            raise ValueError(check)
        counts[target] = counts.get(target, 0) + 1
    if set(counts) != EXPECTED_TARGETS or require_up and any(count != 1 for count in counts.values()):
        raise ValueError(check)
    return [{'component': component, 'replica': replica, 'seriesCount': counts[(component, replica)]}
            for component, replica in sorted(EXPECTED_TARGETS, key=lambda target: (target[0], target[1] or ''))]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prometheus', default='http://127.0.0.1:9090')
    parser.add_argument('--grafana', default='http://127.0.0.1:3000')
    parser.add_argument('--timeout', type=int, default=120)
    parser.add_argument('--report', type=Path, default=ROOT / '.local/reports/monitoring.json')
    args = parser.parse_args()
    password = (ROOT / '.local/monitoring/grafana-admin-password').read_text().strip()
    auth = 'Basic ' + base64.b64encode(('admin:' + password).encode()).decode()
    started = time.monotonic()
    report = {'status': 'FAIL', 'simulated': True,
              'executedAt': datetime.now(timezone.utc).isoformat(), 'checks': [],
              'scope': 'Actual authenticated scrape proxy, two worker replicas, Prometheus and provisioned Grafana. No domain writes.'}
    last_error = 'NotReady'
    while time.monotonic() - started < args.timeout:
        try:
            targets = fetch(args.prometheus + '/api/v1/targets')['data']['activeTargets']
            ours = [t for t in targets if t['labels'].get('job', '').startswith('chargehub')]
            actual = {(t['labels'].get('component'), t['labels'].get('replica')) for t in ours}
            if actual != EXPECTED_TARGETS or len(ours) != 3 or any(t['health'] != 'up' for t in ours):
                raise ValueError('ThreeApplicationTargetsNotUp')
            checks = [{'check': 'API and both worker replicas actually scraped', 'result': 'PASS',
                       'targets': [{'component': t['labels']['component'], 'replica': t['labels'].get('replica'),
                                    'health': t['health']} for t in ours]}]
            for metric in ['http_server_requests_seconds_bucket', 'hikaricp_connections_acquire_seconds_bucket',
                           'chargehub_outbox_oldest_seconds', 'chargehub_funds_differences']:
                query = metric + '{job=~"chargehub.*"}'
                values = fetch(args.prometheus + '/api/v1/query?' + parse.urlencode({'query': query}))['data']['result']
                metric_targets = application_series(values, 'MissingOrNonFiniteRequiredMetric:' + metric)
                checks.append({'check': 'Real metric series: ' + metric, 'result': 'PASS',
                               'seriesCount': len(values), 'targets': metric_targets})
            rules = fetch(args.prometheus + '/api/v1/rules')['data']['groups']
            alerts = [rule for group in rules for rule in group['rules'] if rule['type'] == 'alerting']
            if len(alerts) < 12 or any(rule.get('health') != 'ok' for rule in alerts):
                raise ValueError('AlertRulesNotHealthy')
            checks.append({'check': 'Alert rules loaded and evaluated', 'result': 'PASS', 'ruleCount': len(alerts)})
            if fetch(args.grafana + '/api/health')['database'] != 'ok':
                raise ValueError('GrafanaDatabaseNotReady')
            dashboard = fetch(args.grafana + '/api/dashboards/uid/chargehub-operations', auth)['dashboard']
            if not dashboard['panels']:
                raise ValueError('DashboardEmpty')
            datasource_query = parse.urlencode({'query': 'up{job=~"chargehub.*"}'})
            proxied = fetch(args.grafana + '/api/datasources/proxy/uid/chargehub-prometheus/api/v1/query?' + datasource_query, auth)
            if proxied.get('status') != 'success':
                raise ValueError('GrafanaDatasourceQueryFailed')
            datasource_targets = application_series(proxied['data']['result'], 'GrafanaApplicationTargetsNotUp', require_up=True)
            checks.append({'check': 'Authenticated provisioned Grafana dashboard and live datasource query',
                           'result': 'PASS', 'dashboardUid': dashboard['uid'], 'panels': len(dashboard['panels']),
                           'targets': datasource_targets})
            report.update(status='PASS', checks=checks)
            break
        except Exception as error:
            # Only controlled metric/state labels or the exception type; never URLs, bodies or headers.
            last_error = str(error) if isinstance(error, ValueError) else type(error).__name__
            time.sleep(2)
    if report['status'] != 'PASS':
        report['failure'] = last_error
    report['elapsedSeconds'] = round(time.monotonic() - started, 3)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
    return 0 if report['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
