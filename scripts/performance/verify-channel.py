#!/usr/bin/env python3
"""Verify completed load sessions against independently persisted channel facts.

Run after the performance project is down. Mount its gateway volume read-only,
use --network none, and provide the read-only platform orderNo/payNo TSV export.
The SQLite connection uses mode=ro and includes any committed WAL.
"""
import argparse
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sqlite3


def fingerprints(source):
    return {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
            for p in [source, Path(str(source) + '-wal'), Path(str(source) + '-shm')]
            if p.is_file()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sqlite', type=Path, required=True)
    parser.add_argument('--sessions', type=Path, required=True)
    parser.add_argument('--mapping', type=Path, required=True)
    args = parser.parse_args()
    summary = json.loads(args.sessions.read_text())
    expected = summary.get('sessionResults', [])
    if summary.get('status') != 'PASS' or len(expected) != 100:
        raise SystemExit('Requires the actual PASS summary containing 100 completed sessions')
    pairs = [line.split('\t') for line in args.mapping.read_text().splitlines() if line.strip()]
    if any(len(pair) != 2 for pair in pairs):
        raise SystemExit('Mapping must contain exactly orderNo and payNo columns')
    mapping = dict(pairs)
    if len(mapping) != len(pairs):
        raise SystemExit('Duplicate platform orderNo mappings')
    source = args.sqlite.resolve(strict=True)
    before = fingerprints(source)
    failures, results, trades, providers, refund_ids, refund_providers = [], [], set(), set(), set(), set()
    with closing(sqlite3.connect(source.as_uri() + '?mode=ro', uri=True)) as db:
        db.row_factory = sqlite3.Row
        db.execute('PRAGMA query_only=ON')
        integrity = db.execute('PRAGMA integrity_check').fetchone()[0]
        if integrity != 'ok':
            raise SystemExit('Independent channel SQLite integrity check failed')
        for session in expected:
            order_no = session['orderNo']
            pay_no = mapping.get(order_no)
            if not pay_no or pay_no in trades:
                failures.append({'orderNo': order_no, 'reason': 'missing or reused payNo mapping'})
                continue
            trades.add(pay_no)
            payment = db.execute('SELECT * FROM payments WHERE out_trade_no=?', (pay_no,)).fetchone()
            if payment is None:
                failures.append({'orderNo': order_no, 'reason': 'independent payment missing'})
                continue
            refunds = db.execute('SELECT * FROM refunds WHERE out_trade_no=?', (pay_no,)).fetchall()
            success = [r for r in refunds if r['status'] == 'SUCCESS']
            captured = payment['amount'] if payment['status'] == 'SUCCESS' else 0
            refunded = sum(r['amount'] for r in success)
            valid = (payment['status'] == 'SUCCESS' and payment['currency'] == 'CNY'
                     and captured == session['capturedFen']
                     and refunded == session['refundSuccessFen']
                     and payment['refunded'] == refunded
                     and captured - refunded == session['payableFen']
                     and session['status'] == 'COMPLETED' and session['financialStatus'] == 'CLOSED'
                     and len(success) == len(refunds)
                     and len(success) == (1 if refunded else 0)
                     and all(r['currency'] == 'CNY' for r in refunds))
            if payment['provider_no'] in providers:
                valid = False
            providers.add(payment['provider_no'])
            for refund in refunds:
                if refund['out_refund_no'] in refund_ids or refund['provider_no'] in refund_providers:
                    valid = False
                refund_ids.add(refund['out_refund_no'])
                refund_providers.add(refund['provider_no'])
            if not valid:
                failures.append({'orderNo': order_no, 'reason': 'amount, currency, state, or uniqueness mismatch'})
            results.append({'orderNo': order_no, 'payNo': pay_no,
                            'capturedFen': captured, 'refundSuccessFen': refunded,
                            'channelNetFen': captured - refunded,
                            'platformBillPayableFen': session['payableFen'],
                            'refundCount': len(refunds), 'refundNos': [r['out_refund_no'] for r in refunds],
                            'matched': valid})
    after = fingerprints(source)
    if before != after:
        failures.append({'reason': 'source SQLite or WAL/SHM bytes changed'})
    report = {'status': 'PASS' if not failures and len(results) == 100 else 'FAIL',
              'checkedAt': datetime.now(timezone.utc).isoformat(),
              'runId': summary['runId'], 'method': 'Independent gateway SQLite mode=ro, query_only; exact platform orderNo to payNo join',
              'sourceHashesBefore': before, 'sourceHashesAfter': after, 'sourceUnchanged': before == after,
              'sqliteVersion': sqlite3.sqlite_version,
              'verifierSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'sessionCount': len(expected), 'matchedSessionCount': sum(r['matched'] for r in results),
              'uniquePayNos': len(trades), 'uniqueProviderTradeNos': len(providers),
              'uniqueRefundNos': len(refund_ids), 'uniqueProviderRefundNos': len(refund_providers),
              'totals': {key: sum(r[key] for r in results)
                         for key in ['capturedFen', 'refundSuccessFen', 'channelNetFen', 'platformBillPayableFen']},
              'perOrder': results, 'failures': failures,
              'scope': 'Synthetic independent channel evidence, not real money or production capacity.'}
    print(json.dumps(report, indent=2))
    raise SystemExit(0 if report['status'] == 'PASS' else 1)


if __name__ == '__main__':
    main()
