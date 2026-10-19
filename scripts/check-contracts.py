#!/usr/bin/env python3
"""Validate existing API data against the runtime OpenAPI without changing business state.

Use existing CHARGEHUB_CONTRACT_TOKEN, CHARGEHUB_FINANCE_TOKEN,
CHARGEHUB_OPS_TOKEN and CHARGEHUB_ADMIN_TOKEN for GET-only checks.
--login explicitly permits creating short-lived authentication tokens using
CHARGEHUB_DEMO_PASSWORD; it never creates orders, claims coupons or drives simulators.
No tokens, passwords or response bodies are printed or saved in the report.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

try:
    import yaml
    from jsonschema import Draft4Validator, FormatChecker
    from referencing import Registry, Resource
    from referencing.jsonschema import DRAFT4
except ImportError:
    sys.exit('Optional contract tooling is missing: python3 -m pip install -r requirements-dev.txt')


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT_URI = 'https://chargehub.local/runtime-openapi'


def normalize_openapi(value):
    """OpenAPI 3.0 nullable is translated to the JSON Schema union it represents."""
    if isinstance(value, dict):
        schema = {key: normalize_openapi(item) for key, item in value.items() if key != 'nullable'}
        return {'anyOf': [schema, {'type': 'null'}]} if value.get('nullable') else schema
    if isinstance(value, list):
        return [normalize_openapi(item) for item in value]
    return value


class ContractCheck:
    def __init__(self, api):
        self.api = api.rstrip('/')
        self.document = normalize_openapi(yaml.safe_load((ROOT / 'docs/openapi.yaml').read_text()))
        self.registry = Registry().with_resource(DOCUMENT_URI, Resource.from_contents(self.document, default_specification=DRAFT4))
        self.checks = []
        self.skipped = []

    def validate(self, path, schema, payload, status, expected_status):
        validator = Draft4Validator({'$ref': DOCUMENT_URI + '#/components/schemas/' + schema},
                                    registry=self.registry, format_checker=FormatChecker())
        errors = [{'instancePath': list(error.absolute_path), 'schemaPath': list(error.absolute_schema_path),
                   'validator': error.validator} for error in validator.iter_errors(payload)]
        self.checks.append({'path': path, 'schema': schema, 'httpStatus': status,
                            'passed': not errors and expected_status, 'schemaErrors': errors})

    def call(self, path, schema, token=None, body=None, allowed_errors=()):
        headers = {'Accept': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        raw = None
        if body is not None:
            raw = json.dumps(body).encode()
            headers['Content-Type'] = 'application/json'
        req = Request(self.api + path, raw, headers)
        try:
            with urlopen(req, timeout=10) as response:
                payload = json.load(response)
                self.validate(path, schema, payload, response.status, response.status == 200)
                return payload.get('data')
        except HTTPError as error:
            payload = json.loads(error.read(2 * 1024 * 1024))
            self.validate(path, 'Error', payload, error.code, error.code in allowed_errors)
        except (URLError, TimeoutError, json.JSONDecodeError, UnicodeDecodeError) as error:
            self.checks.append({'path': path, 'schema': schema, 'httpStatus': None, 'passed': False,
                                'transportError': type(error).__name__, 'schemaErrors': []})
        return None

    def login(self, username, password):
        data = self.call('/v1/auth/token', 'TokenResponse', body={'username': username, 'password': password})
        return data.get('accessToken') if data else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api', default='http://127.0.0.1:8080')
    parser.add_argument('--username', default='member')
    parser.add_argument('--finance-username', default='finance')
    parser.add_argument('--ops-username', default='ops')
    parser.add_argument('--admin-username', default='admin')
    parser.add_argument('--max-orders', type=int, default=20)
    parser.add_argument('--login', action='store_true', help='Permit authentication token creation; domain checks still use only GET')
    parser.add_argument('--report', type=Path, default=ROOT / '.local/reports/contracts.json')
    args = parser.parse_args()
    if not 1 <= args.max_orders <= 100:
        parser.error('--max-orders must be between 1 and 100')
    token = os.environ.get('CHARGEHUB_CONTRACT_TOKEN')
    finance = os.environ.get('CHARGEHUB_FINANCE_TOKEN')
    ops = os.environ.get('CHARGEHUB_OPS_TOKEN')
    admin = os.environ.get('CHARGEHUB_ADMIN_TOKEN')
    password = os.environ.get('CHARGEHUB_DEMO_PASSWORD')
    if not token and not (args.login and password):
        parser.error('Set CHARGEHUB_CONTRACT_TOKEN, or use --login with CHARGEHUB_DEMO_PASSWORD')

    check = ContractCheck(args.api)
    if not token:
        token = check.login(args.username, password)
    if token:
        current_user = check.call('/v1/auth/me', 'CurrentUserResponse', token)
        check.call('/v1/stations', 'StationPageResponse', token)
        check.call('/v1/stations?limit=1', 'StationPageResponse', token)
        check.call('/v1/me/benefits', 'BenefitsResponse', token)
        check.call('/v1/me/coupons', 'CouponPageResponse', token)
        check.call('/v1/coupon-templates', 'CouponTemplatePageResponse', token)
        page = check.call('/v1/charge-orders?limit=' + str(args.max_orders), 'OrderPageResponse', token)
        orders = (page or {}).get('items', [])
        for order in orders:
            path = '/v1/charge-orders/' + order['orderNo']
            check.call(path, 'OrderResponse', token)
            check.call(path + '/funds', 'FundsResponse', token)
            check.call(path + '/bill', 'BillResponse', token, allowed_errors=(409,))
        if not orders:
            check.skipped.append({'schema': 'OrderResponse/FundsResponse/BillResponse', 'reason': 'No existing orders visible for selected user'})
        if current_user and not set(current_user.get('roles', [])) & {'OPS', 'FINANCE', 'ADMIN'}:
            check.call('/v1/admin/charge-orders?limit=1', 'StaffOrderPageResponse', token, allowed_errors=(403,))
    if not finance and args.login and password:
        finance = check.login(args.finance_username, password)
    if finance:
        finance_page = check.call('/v1/admin/reconciliation-items', 'ReconciliationPageResponse', finance)
        check.call('/v1/admin/audits?limit=1', 'AuditPageResponse', finance)
        check.call('/v1/admin/charge-orders?limit=1', 'StaffOrderPageResponse', finance)
        differences = (finance_page or {}).get('items', [])
        if differences:
            item = differences[0]
            check.call('/v1/admin/finance/reconciliation-items/' + item['itemId'], 'FinanceItemDetailResponse', finance)
            check.call('/v1/admin/finance/reconciliations/' + item['batchId'], 'FinanceBatchDetailResponse', finance)
        else:
            check.skipped.append({'schema': 'FinanceItemDetailResponse/FinanceBatchDetailResponse', 'reason': 'No existing finance differences; no fixtures created'})
    else:
        check.skipped.append({'schema': 'ReconciliationPageResponse', 'reason': 'No finance token supplied'})

    if not ops and args.login and password:
        ops = check.login(args.ops_username, password)
    if ops:
        staff_page = check.call('/v1/admin/charge-orders?limit=' + str(args.max_orders), 'StaffOrderPageResponse', ops)
        if (staff_page or {}).get('nextCursor'):
            check.call('/v1/admin/charge-orders?limit=1&after=' + staff_page['nextCursor'], 'StaffOrderPageResponse', ops)
        session_order = next((row for row in (staff_page or {}).get('items', []) if row.get('sessionNo')), None)
        if session_order:
            check.call('/v1/admin/charge-orders/' + session_order['orderNo'] + '/meter-review', 'MeterReviewResponse', ops)
        else:
            check.skipped.append({'schema': 'MeterReviewResponse', 'reason': 'No existing staff order with device session in selected page'})
        station_page = check.call('/v1/admin/stations?limit=1', 'AdminStationPageResponse', ops)
        if (station_page or {}).get('nextCursor'):
            check.call('/v1/admin/stations?limit=1&cursor=' + station_page['nextCursor'], 'AdminStationPageResponse', ops)
        stations = (station_page or {}).get('items', [])
        if stations:
            station = stations[0]
            connector_page = check.call('/v1/admin/stations/' + station['id'] + '/connectors', 'AdminConnectorListResponse', ops)
            if station.get('status') == 'ENABLED':
                check.call('/v1/stations/' + station['id'], 'StationResponse', ops)
            if station.get('activeTariffId'):
                check.call('/v1/admin/tariffs/' + station['activeTariffId'], 'TariffResponse', ops)
            connectors = (connector_page or {}).get('items', [])
            if connectors:
                check.call('/v1/connectors/' + connectors[0]['id'] + '/tariff', 'DisplayTariffResponse', ops, allowed_errors=(409,))
        template_page = check.call('/v1/admin/coupon-templates?limit=1', 'AdminCouponTemplatePageResponse', ops)
        templates = (template_page or {}).get('items', [])
        if templates:
            check.call('/v1/admin/coupon-templates/' + templates[0]['id'], 'AdminCouponTemplateRecordResponse', ops)
        if token and current_user:
            check.call('/v1/admin/memberships/' + current_user['id'], 'MembershipRecordResponse', ops, allowed_errors=(404,))
    else:
        check.skipped.append({'schema': 'StaffOrderPageResponse/AdminStationPageResponse/AdminCouponTemplatePageResponse', 'reason': 'No operations token supplied'})

    if not admin and args.login and password:
        admin = check.login(args.admin_username, password)
    if admin:
        users = check.call('/v1/admin/users?limit=1', 'AdminUserPageResponse', admin)
        if (users or {}).get('nextCursor'):
            check.call('/v1/admin/users?limit=1&cursor=' + users['nextCursor'], 'AdminUserPageResponse', admin)
        check.call('/v1/admin/outbox?status=DEAD&limit=1', 'OutboxTaskPageResponse', admin)
        check.call('/v1/admin/outbox?status=DONE&limit=1', 'OutboxTaskPageResponse', admin)
        check.call('/v1/admin/outbox?status=PUBLISHED&limit=1', 'OutboxTaskPageResponse', admin)
    else:
        check.skipped.append({'schema': 'AdminUserPageResponse/OutboxTaskPageResponse', 'reason': 'No administrator token supplied'})

    failed = sum(not row['passed'] for row in check.checks)
    report = {'executedAt': datetime.now(timezone.utc).isoformat(), 'api': args.api,
              'responseChecks': len(check.checks), 'passed': len(check.checks) - failed, 'failed': failed,
              'validatedSchemas': sorted({row['schema'] for row in check.checks if row['passed']}),
              'businessWrites': 0, 'authenticationMode': 'login-permitted' if args.login else 'existing-token',
              'checks': check.checks, 'skipped': check.skipped}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({key: report[key] for key in ('responseChecks', 'passed', 'failed', 'validatedSchemas', 'businessWrites', 'skipped')}, ensure_ascii=False))
    return bool(failed)


if __name__ == '__main__':
    raise SystemExit(main())
