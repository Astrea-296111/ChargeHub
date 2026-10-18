#!/usr/bin/env python3
"""Meaningful live-API smoke acceptance; not a claim that all A01–A47 pass.

Mutates the isolated development database and both independent simulators. It
does not import server classes or update the application database directly.
"""
from __future__ import annotations

import json
import sys
import time
import uuid
from datetime import datetime, timezone
from demo import (ApiError, Client, arguments, available_connectors, environment,
                  expect, reset_faults, settle, wait_for)


def main():
    args = arguments('ChargeHub live API acceptance — isolated SIMULATION environment only').parse_args()
    password, control_key = environment()
    user, other = Client(args.api), Client(args.api)
    user.login('customer', password)
    other.login('member', password)
    gateway, device = Client(args.gateway, control_key=control_key), Client(args.device, control_key=control_key)
    checks = []

    def passed(name, evidence):
        checks.append({'check': name, 'result': 'PASS', 'evidence': evidence})
        print('PASS ' + name, flush=True)

    def rejected(name, function, statuses):
        try:
            function()
        except ApiError as exc:
            expect(exc.status in statuses, f'{name}: expected {statuses}, got {exc}')
            passed(name, {'httpStatus': exc.status, 'code': exc.payload.get('code')})
            return
        raise AssertionError(name + ': request unexpectedly succeeded')

    reset_faults(gateway, device)
    rejected('Unauthenticated station request', lambda: Client(args.api).call('/v1/stations'), (401,))
    rejected('Invalid login password', lambda: Client(args.api).login('customer', 'intentionally-wrong-' + str(uuid.uuid4())), (401,))
    rejected('Unsigned payment notification', lambda: Client(args.api).call('/integrations/payments/mock/notifications', {}), (400, 401, 403))
    rejected('Customer cannot publish tariff', lambda: user.call('/v1/admin/tariffs/1/publish', {}), (403,))
    rejected('Invalid query binding returns 400', lambda: user.call('/v1/charge-orders?cursor=not-a-number'), (400,))

    connectors = available_connectors(user)
    expect(len(connectors) >= 2, 'At least two free connectors are needed; use a clean isolated development database')
    (_, connector), (_, alternative) = connectors[:2]
    selection = {'connectorId': connector['connectorId'], 'useMembership': False, 'couponId': None}
    quote = user.call('/v1/charge-quotes', {**selection, 'estimatedEnergyWh': 20000})
    create = {**selection, 'expectedTariffVersion': quote['tariffVersion']}
    rejected('Missing Idempotency-Key returns 400',
             lambda: user.call('/v1/charge-orders', create, include_key=False), (400,))
    key = str(uuid.uuid4())
    order = user.call('/v1/charge-orders', create, key=key)
    no, path = order['orderNo'], '/v1/charge-orders/' + order['orderNo']
    repeated = user.call('/v1/charge-orders', create, key=key)
    expect(repeated['orderNo'] == no, 'Same idempotency key created a second order')
    passed('A11 Same key returns original order', {'orderNo': no})
    rejected('A12 Same key with changed connector conflicts',
             lambda: user.call('/v1/charge-orders', {**create, 'connectorId': alternative['connectorId']}, key=key), (409,))
    rejected('A02 Another user cannot read order', lambda: other.call(path), (403, 404))
    rejected('A02 Another user cannot stop order', lambda: other.call(path + '/stop', {}), (403, 404))
    rejected('Another user cannot read funds', lambda: other.call(path + '/funds'), (403, 404))
    rejected('Another user cannot read bill', lambda: other.call(path + '/bill'), (403, 404))
    rejected('Same user cannot occupy a second connector',
             lambda: user.call('/v1/charge-orders', {**create, 'connectorId': alternative['connectorId']}), (409,))
    rejected('Occupied connector rejects another user', lambda: other.call('/v1/charge-orders', create), (409,))
    expect(user.call(path)['status'] == 'WAIT_PAY', 'Unauthorized requests changed the order state')
    passed('Unauthorized requests preserve WAIT_PAY state', {'orderNo': no, 'status': 'WAIT_PAY'})
    rejected('Bill unavailable before service is complete', lambda: user.call(path + '/bill'), (409,))

    # 20 extra deliveries carry the same immutable channel event with fresh nonces.
    gateway.call('/dev/faults', {'duplicateNotificationCount': 20})
    try:
        result = settle(user, gateway, device, order, 2600)
        # Let all deliberately duplicated delivery attempts finish before observing cash.
        time.sleep(1)
        funds = user.call(path + '/funds')
        expect(funds['capturedFen'] == 10000 and funds['refundSuccessFen'] == 7400,
               f'Duplicate payment or refund notifications changed cash: {funds}')
        expect(funds['netReceivedFen'] == 2600, 'Cash net must equal final payable')
        passed('A14 Duplicate notifications preserve one financial effect',
               {'duplicateNotificationCount': 20, 'funds': funds, 'sessionNo': result['sessionNo']})
        passed('20 kWh ordinary-user billing and refund close', {'amounts': result['bill']['amounts']})
    finally:
        reset_faults(gateway, device)

    # Resource release is checked through a subsequent order on the original gun.
    quote2 = user.call('/v1/charge-quotes', {**selection, 'estimatedEnergyWh': 1000})
    second = user.call('/v1/charge-orders', {**selection, 'expectedTariffVersion': quote2['tariffVersion']})
    cancel_path = '/v1/charge-orders/' + second['orderNo']
    user.call(cancel_path + '/cancel', {})
    canceled = wait_for('unpaid cancellation', lambda: user.call(cancel_path), lambda value: value['status'] == 'CANCELED')
    canceled_funds = user.call(cancel_path + '/funds')
    expect(canceled_funds['capturedFen'] == 0 and canceled_funds['refundSuccessFen'] == 0,
           'Unpaid canceled order must not create cash')
    passed('Unpaid cancellation and resource reuse', {'orderNo': canceled['orderNo'], 'funds': canceled_funds})

    report = {'simulated': True, 'executedAt': datetime.now(timezone.utc).isoformat(),
              'scope': 'Live API smoke acceptance; A01–A47 full fault/performance acceptance remains separate',
              'status': 'PASS', 'checks': checks}
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, AssertionError, KeyError, StopIteration) as exc:
        print('FAIL: ' + str(exc), file=sys.stderr)
        sys.exit(1)
