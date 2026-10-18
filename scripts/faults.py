#!/usr/bin/env python3
"""Live recovery checks against the isolated ChargeHub development services.

Uses public application HTTP APIs and authenticated simulator controls. No direct
database writes and no membership/coupon consumption. Source .local/dev.env first.
Run this sequentially, after demo.py/acceptance.py; simulator faults are global.
"""
from __future__ import annotations

import json
import sys
import time
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP
from zoneinfo import ZoneInfo

from demo import (ApiError, Client, arguments, available_connectors, environment,
                  expect, reset_faults, settle, wait_for)


def create_order(client):
    connectors = available_connectors(client)
    expect(bool(connectors), 'No free connector for the recovery scenario')
    selection = {'connectorId': connectors[0][1]['connectorId'], 'useMembership': False, 'couponId': None}
    quote = client.call('/v1/charge-quotes', {**selection, 'estimatedEnergyWh': 20000})
    return client.call('/v1/charge-orders', {**selection, 'expectedTariffVersion': quote['tariffVersion']})


def start_order(client, gateway, order):
    path = '/v1/charge-orders/' + order['orderNo']
    payment = client.call(path + '/payment', {})
    wait_for('independent gateway capture', lambda: gateway.call('/dev/payments/' + payment['payNo'] + '/capture', {}),
             lambda value: value['status'] == 'SUCCESS')
    charging = wait_for('recovery to authoritative CHARGING', lambda: client.call(path),
                        lambda value: value['status'] == 'CHARGING', timeout=45)
    return payment, charging


def completed(client, gateway, device, order, payment, charging, expected_energy=1600, expected_service=1000):
    path = '/v1/charge-orders/' + order['orderNo']
    bill = wait_for('complete formal bill', lambda: client.call(path + '/bill'), lambda value: 'amounts' in value)
    amounts = bill['amounts']
    payable = expected_energy + expected_service
    expect(amounts['totalWh'] == 20000, f'Incorrect cumulative energy: {amounts}')
    expect(amounts['energyFen'] == expected_energy and amounts['serviceFen'] == expected_service,
           f'Actual frozen-rate segments produced incorrect charges: {amounts}')
    expect(amounts['memberDiscountFen'] == 0 and amounts['couponDiscountFen'] == 0,
           'Recovery checks must not consume member or coupon benefits')
    expect(amounts['payableFen'] == payable, f'Incorrect payable: {amounts}')
    funds = wait_for('query-based refund recovery and financial close', lambda: client.call(path + '/funds'),
                     lambda value: value['financialStatus'] == 'CLOSED', timeout=45)
    expect(funds['capturedFen'] == 10000 and funds['refundSuccessFen'] == 10000 - payable,
           f'Capture or refund effects were lost or duplicated: {funds}')
    expect(funds['refundReservedFen'] == 0 and funds['netReceivedFen'] == payable,
           f'Refund reservation or cash balance is incorrect: {funds}')
    state = gateway.call('/dev/state', {})
    refunds = [value for value in state['refunds'] if value['payNo'] == payment['payNo']]
    expect(len(refunds) == 1 and refunds[0]['status'] == 'SUCCESS' and refunds[0]['amountFen'] == 10000 - payable,
           'Gateway must retain exactly one successful refund under one stable identifier')
    sessions = [value for value in device.call('/dev/state', {})['sessions'] if value['orderNo'] == order['orderNo']]
    expect(len(sessions) == 1 and sessions[0]['status'] == 'STOPPED', 'Exactly one actual device session must have ended')
    final = client.call(path)
    expect(final['status'] == 'COMPLETED', f'Recovery left the order unfinished: {final}')
    return {'orderNo': order['orderNo'], 'payNo': payment['payNo'], 'sessionNo': charging['sessionNo'],
            'amounts': amounts, 'funds': funds, 'refundNo': refunds[0]['refundNo']}


def payment_notification_lost(client, gateway, device):
    gateway.call('/dev/faults', {'dropNotification': True})
    order = create_order(client)
    result = settle(client, gateway, device, order, 2600)
    channel = gateway.call('/dev/state', {})
    events = {value['channelEventId'] for value in channel['payments'] + channel['refunds']
              if value['payNo'] == result['payNo']}
    notifications = [value for value in channel['notifications'] if value['event_id'] in events]
    expect(len(notifications) == 2 and all(not value['delivered'] for value in notifications),
           'Recovery evidence requires payment and refund notifications to remain undelivered')
    return {'orderNo': order['orderNo'], 'payNo': result['payNo'], 'funds': result['funds'],
            'undeliveredChannelEvents': sorted(events)}


def start_response_lost(client, gateway, device):
    # Suppress STARTED delivery as well: recovery must consult the actual device.
    device.call('/dev/faults', {'deviceStartTimeoutButRunning': True, 'dropNotification': True})
    order = create_order(client)
    payment, charging = start_order(client, gateway, order)
    funds = client.call('/v1/charge-orders/' + order['orderNo'] + '/funds')
    expect(funds['refundReservedFen'] == 0 and funds['refundSuccessFen'] == 0,
           'Unknown start result must not prematurely refund a running session')
    sessions = [value for value in device.call('/dev/state', {})['sessions'] if value['orderNo'] == order['orderNo']]
    expect(len(sessions) == 1 and sessions[0]['status'] == 'CHARGING', 'Actual running session was lost or duplicated')
    device.call('/dev/faults', {'deviceStartTimeoutButRunning': False, 'dropNotification': False})
    device.call('/dev/notifications/retry', {})
    device.call('/dev/sessions/' + charging['sessionNo'] + '/demo', {})
    return completed(client, gateway, device, order, payment, charging)


def refund_response_and_notification_lost(client, gateway, device):
    order = create_order(client)
    payment, charging = start_order(client, gateway, order)
    gateway.call('/dev/faults', {'dropResponseAfterCommit': True, 'dropNotification': True})
    device.call('/dev/sessions/' + charging['sessionNo'] + '/demo', {})
    result = completed(client, gateway, device, order, payment, charging)
    channel = gateway.call('/dev/state', {})
    refund = next(value for value in channel['refunds'] if value['refundNo'] == result['refundNo'])
    notification = next(value for value in channel['notifications'] if value['event_id'] == refund['channelEventId'])
    expect(not notification['delivered'], 'Refund notification must remain lost while query recovery closes cash')
    result['undeliveredRefundEvent'] = refund['channelEventId']
    return result


def metering_boundary_lost(client, gateway, device):
    order = create_order(client)
    payment, charging = start_order(client, gateway, order)
    session = next(value for value in device.call('/dev/state', {})['sessions'] if value['sessionNo'] == charging['sessionNo'])
    start = datetime.fromisoformat(session['startedAt'].replace('Z', '+00:00'))
    zone = ZoneInfo('Asia/Shanghai')
    # Seeded dev tariff changes at 08:00, 18:00, 22:00 and local midnight.
    candidates = [datetime.combine(start.astimezone(zone).date() + timedelta(days=day), datetime.min.time(), zone)
                  + timedelta(minutes=minute) for day in (0, 1) for minute in (0, 480, 1080, 1320)]
    boundary = min(value for value in candidates if value > start)
    end = boundary + timedelta(minutes=30)
    device.call('/dev/faults', {'dropBoundarySample': True})
    snapshot = device.call('/dev/sessions/' + charging['sessionNo'] + '/advance',
                           {'deltaWh': 20000, 'occurredAt': end.astimezone(timezone.utc).isoformat(), 'stop': True})
    boundary_events = [event for event in snapshot['events'] if event['sampleKind'].endswith('BOUNDARY')]
    expect(bool(boundary_events), 'Scenario did not cross a tariff/day boundary')
    notified_ids = {value['event_id'] for value in device.call('/dev/state', {})['notifications']}
    expect(all(event['eventId'] not in notified_ids for event in boundary_events),
           'Boundary fault must omit original transport notifications while retaining device evidence')
    path = '/v1/charge-orders/' + order['orderNo']
    observed_gap = False
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        current = client.call(path)
        if current['status'] == 'REVIEW':
            try:
                client.call(path + '/bill')
            except ApiError as exc:
                expect(exc.status == 409, 'Incomplete metering must not return a formal bill')
                observed_gap = True
            break
        if current['status'] == 'COMPLETED':
            break  # A fast recovery query can replay the missing boundary before the first observation.
        time.sleep(.02)
    rates = [(0, 480, 50, 30), (480, 1080, 80, 50), (1080, 1320, 120, 60), (1320, 1440, 50, 30)]
    energy, service = 0, 0
    points = [snapshot['events'][0], *boundary_events, snapshot['events'][-1]]
    for first, last in zip(points, points[1:]):
        local = datetime.fromisoformat(first['occurredAt'].replace('Z', '+00:00')).astimezone(zone)
        minute = local.hour * 60 + local.minute
        rate = next(value for value in rates if value[0] <= minute < value[1])
        delta = last['meterWh'] - first['meterWh']
        energy += int((Decimal(delta) * rate[2] / 1000).quantize(Decimal('1'), rounding=ROUND_HALF_UP))
        service += int((Decimal(delta) * rate[3] / 1000).quantize(Decimal('1'), rounding=ROUND_HALF_UP))
    result = completed(client, gateway, device, order, payment, charging, energy, service)
    result['missingBoundaryEventIds'] = [event['eventId'] for event in boundary_events]
    result['incompleteBillObserved'] = observed_gap
    result['recovery'] = 'Original device boundary replay; no energy interpolation'
    return result


SCENARIOS = {'payment-notification-lost': payment_notification_lost,
             'start-response-lost': start_response_lost,
             'refund-response-and-notification-lost': refund_response_and_notification_lost,
             'metering-boundary-lost': metering_boundary_lost}


def main():
    parser = arguments('ChargeHub fault recovery — SIMULATION only; sequential isolated dev services')
    parser.add_argument('--scenario', action='append', choices=SCENARIOS, help='Repeat to select scenarios; default runs all')
    args = parser.parse_args()
    password, key = environment()
    client = Client(args.api)
    client.login('customer', password)
    gateway, device = Client(args.gateway, control_key=key), Client(args.device, control_key=key)
    checks = []
    for name in args.scenario or SCENARIOS:
        reset_faults(gateway, device)
        try:
            evidence = SCENARIOS[name](client, gateway, device)
            checks.append({'check': name, 'status': 'PASS', 'evidence': evidence})
            print('PASS ' + name, flush=True)
        finally:
            reset_faults(gateway, device)
            gateway.call('/dev/notifications/retry', {})
            device.call('/dev/notifications/retry', {})
    print(json.dumps({'simulated': True, 'executedAt': datetime.now(timezone.utc).isoformat(),
                      'scope': 'Four targeted live recovery cases; not full A01–A47 acceptance',
                      'status': 'PASS', 'checks': checks}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, AssertionError, KeyError, StopIteration) as exc:
        print('FAIL: ' + str(exc), file=sys.stderr)
        sys.exit(1)
