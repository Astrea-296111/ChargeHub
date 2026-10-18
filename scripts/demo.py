#!/usr/bin/env python3
"""Run the documented 20 kWh scenario against the actual three local processes.

Only Python's standard library is needed. Credentials come from the environment;
this client never reads or prints secret files. Source .local/dev.env in the shell.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import uuid
from datetime import datetime, timezone
from urllib import error, request


class ApiError(RuntimeError):
    def __init__(self, status, payload):
        self.status = status
        self.payload = payload
        super().__init__(f"HTTP {status}: {payload.get('code', 'ERROR')} — {payload.get('message', '')}")


class Client:
    def __init__(self, base_url, token=None, control_key=None):
        self.base_url = base_url.rstrip('/')
        self.token = token
        self.control_key = control_key

    def call(self, path, body=None, method=None, key=None, include_key=True):
        method = method or ('POST' if body is not None else 'GET')
        headers = {'Accept': 'application/json'}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        if self.control_key:
            headers['X-Control-Key'] = self.control_key
        if method != 'GET':
            headers['Content-Type'] = 'application/json'
            if include_key:
                headers['Idempotency-Key'] = key or str(uuid.uuid4())
        raw = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        req = request.Request(self.base_url + path, raw, headers, method=method)
        try:
            with request.urlopen(req, timeout=10) as response:
                payload = json.loads(response.read(2 * 1024 * 1024))
        except error.HTTPError as exc:
            try:
                payload = json.loads(exc.read(1024 * 1024))
            except (ValueError, UnicodeDecodeError):
                payload = {'code': 'INVALID_ERROR_RESPONSE', 'message': 'Server returned a non-JSON error'}
            raise ApiError(exc.code, payload) from None
        except (error.URLError, TimeoutError) as exc:
            raise RuntimeError(f'Cannot reach {self.base_url}: {type(exc).__name__}') from None
        if payload.get('code') != 'OK':
            raise ApiError(200, payload)
        return payload.get('data')

    def login(self, username, password):
        result = self.call('/v1/auth/token', {'username': username, 'password': password})
        self.token = result['accessToken']
        return result


def items(value):
    return value if isinstance(value, list) else value.get('items', [])


def expect(condition, message):
    if not condition:
        raise AssertionError(message)


def wait_for(description, fetch, predicate, timeout=40):
    """Poll until an observed server result meets the condition; never invent success."""
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            last = fetch()
            if predicate(last):
                return last
        except ApiError as exc:
            if exc.status not in (404, 409):
                raise
            last = {'code': exc.payload.get('code')}
        time.sleep(.25)
    raise AssertionError(f'Timed out waiting for {description}; last observed result: {json.dumps(last, ensure_ascii=False)}')


def environment():
    password = os.environ.get('CHARGEHUB_DEMO_PASSWORD', '')
    control_key = os.environ.get('CHARGEHUB_CONTROL_KEY', '')
    if not password or not control_key:
        raise RuntimeError('Source .local/dev.env in the shell first: CHARGEHUB_DEMO_PASSWORD and CHARGEHUB_CONTROL_KEY are required.')
    return password, control_key


def arguments(description):
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument('--api', default=os.environ.get('CHARGEHUB_API_URL', 'http://127.0.0.1:8080'))
    parser.add_argument('--gateway', default=os.environ.get('CHARGEHUB_GATEWAY_URL', 'http://127.0.0.1:18081'))
    parser.add_argument('--device', default=os.environ.get('CHARGEHUB_DEVICE_URL', 'http://127.0.0.1:18082'))
    return parser


def reset_faults(gateway, device):
    disabled = {'dropResponseAfterCommit': False, 'dropNotification': False,
                'duplicateNotificationCount': 0, 'notificationDelayMs': 0,
                'deviceStartTimeoutButRunning': False, 'deviceStopAckLost': False,
                'meterReorderWindow': 0, 'dropBoundarySample': False,
                'injectMeterRollback': False, 'temporaryFailure': False, 'rejectRefund': False}
    gateway.call('/dev/faults', disabled)
    device.call('/dev/faults', disabled)


def available_connectors(client):
    return [(station, connector)
            for station in items(client.call('/v1/stations'))
            for connector in station['connectors'] if connector['available']]


def verify_settlement(client, gateway, device, no, expected_payable,
                      expected_member=0, expected_coupon=0, pay_no=None):
    """Verify current application/channel/device facts without creating any effect."""
    path = '/v1/charge-orders/' + no
    bill = wait_for('formal bill', lambda: client.call(path + '/bill'), lambda value: 'amounts' in value)
    amounts = bill['amounts']
    expect(amounts['totalWh'] == 20000, f'Expected 20 kWh, got {amounts}')
    expect(amounts['energyFen'] == 1600 and amounts['serviceFen'] == 1000,
           f'Expected afternoon tariff energy=1600 and service=1000, got {amounts}')
    expect(amounts['memberDiscountFen'] == expected_member, f'Incorrect membership discount: {amounts}')
    expect(amounts['couponDiscountFen'] == expected_coupon, f'Incorrect coupon discount: {amounts}')
    expect(amounts['payableFen'] == expected_payable, f'Incorrect payable: {amounts}')
    funds = wait_for('refund and financial close', lambda: client.call(path + '/funds'),
                     lambda value: value['financialStatus'] == 'CLOSED')
    expect(funds['capturedFen'] == 10000, f'Expected exactly one 10000 fen capture: {funds}')
    expect(funds['refundSuccessFen'] == 10000 - expected_payable, f'Incorrect successful refund: {funds}')
    expect(funds['refundReservedFen'] == 0, f'Refund reservation not released: {funds}')
    expect(funds['netReceivedFen'] == expected_payable, f'Cash does not equal bill: {funds}')
    channel = gateway.call('/dev/state', {})
    local_refund_nos = {r['refundNo'] for r in funds['refunds']}
    related_channel_refunds = [r for r in channel['refunds'] if r['outRefundNo'] in local_refund_nos]
    # The strict Funds API intentionally omits payment internals. A real refund's
    # stable refundNo links the application order to the independent channel payNo.
    if pay_no is None:
        related_pay_nos = {r['outTradeNo'] for r in related_channel_refunds}
        expect(len(related_pay_nos) == 1, 'Cannot link existing order refunds to one independent channel payment')
        pay_no = related_pay_nos.pop()
    expect(all(r['outTradeNo'] == pay_no for r in related_channel_refunds),
           'Application refunds refer to multiple channel payments')
    channel_payments = [p for p in channel['payments'] if p['outTradeNo'] == pay_no]
    channel_refunds = [r for r in channel['refunds'] if r['outTradeNo'] == pay_no and r['status'] == 'SUCCESS']
    expect(len(channel_payments) == 1 and channel_payments[0]['status'] == 'SUCCESS',
           'Independent gateway must retain one successful payment fact')
    expect(channel_payments[0]['amountFen'] == 10000 and channel_payments[0]['currency'] == 'CNY',
           'Independent gateway payment amount or currency differs from the platform')
    expect({r['outRefundNo'] for r in channel_refunds} == local_refund_nos,
           'Application and independent gateway refund identifiers disagree')
    expect(sum(r['amountFen'] for r in channel_refunds) == 10000 - expected_payable,
           'Independent gateway refund total differs from application cash')
    sessions = [s for s in device.call('/dev/state', {})['sessions'] if s['orderNo'] == no]
    expect(len(sessions) == 1, 'Independent device must create exactly one actual session per order')
    expect(sessions[0]['status'] == 'STOPPED', 'Independent device has not confirmed stop')
    expect([e['seqNo'] for e in sessions[0]['events']] == list(range(6)),
           'Independent device must retain the complete seq0..seq5 measurement chain')
    final = client.call(path)
    expect(final['status'] == 'COMPLETED', f'Final service status is not completed: {final}')
    expect(final.get('sessionNo') == sessions[0]['sessionNo'], 'Application and independent device session identifiers disagree')
    return {'orderNo': no, 'payNo': pay_no, 'sessionNo': sessions[0]['sessionNo'],
            'bill': bill, 'funds': funds, 'order': final}


def settle(client, gateway, device, order, expected_payable, expected_member=0, expected_coupon=0):
    """Capture via independent gateway, then emit device data via independent device."""
    no = order['orderNo']
    path = '/v1/charge-orders/' + no
    payment = client.call(path + '/payment', {})
    expect(payment['amountFen'] == 10000, 'Platform must fix the prepayment to 10000 fen')
    # Channel creation is asynchronous. The simulator may not know the trade yet.
    wait_for('channel trade creation', lambda: gateway.call('/dev/payments/' + payment['payNo'] + '/capture', {}),
             lambda result: result.get('status') == 'SUCCESS')
    charging = wait_for('device STARTED', lambda: client.call(path), lambda value: value['status'] == 'CHARGING')
    expect(bool(charging.get('sessionNo')), 'Order must expose its actual device sessionNo')
    device.call('/dev/sessions/' + charging['sessionNo'] + '/demo', {})
    return verify_settlement(client, gateway, device, no, expected_payable,
                             expected_member, expected_coupon, payment['payNo'])


def existing_documented_sample(client):
    """Find an existing exact A05 sample; its actual facts are verified separately."""
    expected = {'totalWh': 20000, 'energyFen': 1600, 'serviceFen': 1000,
                'memberDiscountFen': 200, 'couponDiscountFen': 300, 'payableFen': 2100}
    cursor, seen = None, set()
    while True:
        query = '?limit=100' + ('&cursor=' + str(cursor) if cursor else '')
        page = client.call('/v1/charge-orders' + query)
        for order in items(page):
            if order['status'] != 'COMPLETED' or order['financialStatus'] != 'CLOSED':
                continue
            bill = client.call('/v1/charge-orders/' + order['orderNo'] + '/bill')
            if all(bill['amounts'].get(k) == v for k, v in expected.items()):
                return order
        cursor = page.get('nextCursor') if isinstance(page, dict) else None
        if not cursor:
            return None
        expect(cursor not in seen, 'Order API returned a repeated pagination cursor')
        seen.add(cursor)


def print_summary(result, reused=False):
    summary = {'simulated': True, 'executedAt': datetime.now(timezone.utc).isoformat(),
               'reusedExisting': reused, 'orderNo': result['orderNo'], 'payNo': result['payNo'],
               'sessionNo': result['sessionNo'], 'amounts': result['bill']['amounts'],
               'funds': result['funds'], 'status': 'PASS'}
    print(json.dumps(summary, ensure_ascii=False, indent=2))


def main():
    parser = arguments('ChargeHub SIMULATION — documented member + coupon, 20 kWh closed loop')
    parser.add_argument('--order', help='Complete an existing WAIT_PAY order created in the browser')
    parser.add_argument('--username', default='member', help='Order owner for --order (default: member)')
    args = parser.parse_args()
    password, control_key = environment()
    client = Client(args.api)
    gateway, device = Client(args.gateway, control_key=control_key), Client(args.device, control_key=control_key)
    client.login(args.username, password)
    print('SIMULATION ONLY — no real money or hardware. Amounts below are integer fen.')
    if not args.order:
        existing = existing_documented_sample(client)
        if existing:
            result = verify_settlement(client, gateway, device, existing['orderNo'], 2100, 200, 300)
            print_summary(result, reused=True)
            return
    reset_faults(gateway, device)
    before = client.call('/v1/me/benefits')
    expected_member, expected_coupon = 200, 300
    if args.order:
        order = client.call('/v1/charge-orders/' + args.order)
        expect(order['status'] == 'WAIT_PAY', 'Existing order must be WAIT_PAY')
        locked = [c for c in items(client.call('/v1/me/coupons'))
                  if c['status'] == 'LOCKED' and c.get('lockedOrderNo') == args.order]
        expected_member = 200 if before['memberActive'] and before['reservedCount'] > 0 else 0
        coupon_id = locked[0]['couponId'] if locked else None
        remaining_service = 1000 - expected_member
        expected_coupon = min(remaining_service, locked[0]['discountFen']) if locked and remaining_service >= locked[0]['thresholdFen'] else 0
    else:
        expect(before['memberActive'], 'Normal documented demo requires the seeded member account')
        expect(before['totalCount'] - before['reservedCount'] - before['usedCount'] > 0,
               'No remaining member quota. Use a fresh isolated development database for this scenario.')
        coupons = [c for c in items(client.call('/v1/me/coupons')) if c['status'] == 'AVAILABLE']
        if not coupons:
            templates = items(client.call('/v1/coupon-templates'))
            expect(bool(templates), 'No claimable coupon template. Use a fresh isolated development database.')
            claimed = client.call('/v1/coupon-templates/' + templates[0]['templateId'] + '/claims', {})
            coupons = [claimed]
        expect(coupons[0]['status'] == 'AVAILABLE',
               'The member coupon is already consumed or expired. Use a fresh isolated development database.')
        coupon_id = coupons[0]['couponId']
        connectors = available_connectors(client)
        expect(bool(connectors), 'No available connector')
        _, connector = connectors[0]
        selection = {'connectorId': connector['connectorId'], 'useMembership': True, 'couponId': coupon_id}
        quote = client.call('/v1/charge-quotes', {**selection, 'estimatedEnergyWh': 20000})
        order = client.call('/v1/charge-orders', {**selection, 'expectedTariffVersion': quote['tariffVersion']})
    result = settle(client, gateway, device, order, 2600 - expected_member - expected_coupon, expected_member, expected_coupon)
    after = client.call('/v1/me/benefits')
    expect(after['reservedCount'] == 0, 'Membership reservation was not released')
    # The --order mode starts after a browser-side reservation, so the used count still increments once.
    expect(after['usedCount'] == before['usedCount'] + int(expected_member > 0), 'Membership usage changed by an incorrect amount')
    if coupon_id and expected_coupon:
        coupon = next(c for c in items(client.call('/v1/me/coupons')) if c['couponId'] == coupon_id)
        expect(coupon['status'] == 'USED', f'Coupon was not consumed: {coupon}')
    print_summary(result)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, AssertionError, KeyError, StopIteration) as exc:
        print('FAIL: ' + str(exc), file=sys.stderr)
        sys.exit(1)
