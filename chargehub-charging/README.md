# Charging and metering

The module separates the charging order, physical session, authenticated raw device events, accepted meter samples, immutable bills, and financial state. `SessionService` performs no network I/O. The worker queries the independent device outside transactions, then submits original events to the same ingestion path.

## Device facts and stop safety

- `deviceId + connectorId + sessionNo` must match the persisted session. Wrong ownership is rejected while retaining the rejected raw evidence; it cannot authorize an order or poison another device's valid billing sequence.
- Event IDs deduplicate transport retries. A changed payload is appended to `device_event_conflict`; the original event payload/hash is retained. Distinct events claiming the same sequence remain separate evidence and cannot overwrite an accepted sample.
- Billing requires START, END, every sequence from zero to `finalSeq`, monotonic cumulative Wh, and an actual sample at each tariff and local midnight boundary. Ordinary samples do not introduce rounding segments. No time interpolation, manual meter entry, or guessed monetary amount is allowed.
- An authenticated STOPPED establishes physical termination even when its meter value needs review. The connector's binding is conditionally released. A last reported FAULT/OFFLINE is preserved rather than exposing a faulty connector as available.
- An ended session can remain REVIEW while its customer is allowed to start another order. This requires a conservative maximum-rate and per-segment rounding bound to prove the entire old service fits within captured, unrefunded funds, and no DIFF/SHORTFALL. Otherwise the user remains blocked. Late facts release only bindings that still name their original order.
- The platform's prepaid backstop uses **pre-benefit** frozen-rate charges and a 95% stop margin. Uncertified sequence/boundary data results in a conservative stop intent. The real final bill is never truncated to the prepayment; an excess remains SHORTFALL.
- A previously recorded financial DIFF/SHORTFALL and its reason survive billing. Producing a bill cannot clear an unrelated financial discrepancy.

`V5__meter_review.sql` adds physical stop proof, immutable conflict payloads, replay requests, review proposals/decisions and append-only sample selections. It does not alter original `device_event`, `meter_sample`, or issued bill contents to implement a correction.

## Operations API

All routes require an authenticated server role. Every write requires `Idempotency-Key` and a 5–255 character `reason`. Unknown body fields are rejected; in particular no route accepts a manually entered Wh value or bill amount.

| Route | Roles | Meaning |
| --- | --- | --- |
| `GET /v1/admin/charge-orders/{no}/meter-review` | OPS, FINANCE, ADMIN | Checklist, raw facts/hashes/conflicting payloads, effective samples, missing ranges, validation, replay requests and decisions. Evidence lists are bounded to 1000; this bound is returned in policy metadata. |
| `POST .../{no}/meter-replay` | OPS, FINANCE, ADMIN | Body `fromSeq`, `toSeq`, `reason`. Persist a bounded range request and a stable `DeviceMeterReplayRequested` event keyed by request number. |
| `POST .../{no}/meter-review/proposals` | OPS, ADMIN | Body `selectedDeviceEventIds`, `reason`. Select one existing original device event for each disputed sequence. Freeze IDs, hashes, evidence cutoffs, and the complete evidence-set hash. |
| `POST .../{no}/meter-review/proposals/{proposalNo}/decision` | FINANCE, ADMIN | Body `decision: APPROVE | REJECT`, `reason`. A different account must decide. Approval verifies the unchanged evidence basis and a complete, valid resulting sequence before appending selections and a decision. |

A proposal does not change billing. The approver cannot be the proposer, including when both hold ADMIN. New evidence invalidates a pending proposal. Approval covers only the explicitly observed evidence IDs and conflicting payload cutoffs; later conflicts need another decision. Earlier decisions and selections remain present, and the latest approved selection is the effective projection. Original raw facts and accepted samples are never overwritten or deleted.

An already issued bill rejects replay/proposal/decision changes with `BILL_IMMUTABLE`. Financial disputes after issuance use the independent financial operations flow; metering operations cannot rewrite the bill. Human selection cannot invent an absent boundary or change the device-declared final sequence. The device must supply the missing/corrected original fact first.

The worker passes its network response to `SessionOperationsService.fulfillReplay(requestNo, response)`. The method filters the approved range, ingests each original fact through the ordinary service, records response hash/count and audit, then tries finalization. UNKNOWN is WAITING and incomplete delivery is PARTIAL; both cause a retryable error after progress is committed. DONE means the requested facts were received, not that a conflicted bill was silently approved.

## Independent simulator

`simulators/device.py` owns its SQLite session/command/event facts. A missing session is UNKNOWN until an explicit rejection or an expired start window is durably fenced; NOT_STARTED can never be followed by accepting a late START for that session. Expiry uses an exclusive deadline. Repeated commands keep their business identity and result.

Authenticated development controls include `deviceStartRejected`, `deviceQueryUnknown`, `deviceStartTimeoutButRunning`, `deviceStopAckLost`, `dropNotification`, `dropBoundarySample`, `dropMeterSeqNos`, `meterReorderWindow`, and `injectMeterRollback`. Query supports `fromSeq`/`toSeq`. `/dev/sessions/{session}/stop` models autonomous hardware stopping without additional energy. `/correct` publishes an additional event from the simulator's retained physical truth; it never edits the bad original and is not a platform manual-meter endpoint. These controls are simulation fixtures, not production OCPP support.

## Validation

- `SessionServiceTest`: 15 focused tests, including exact segment rounding, boundary/gap rejection, zero-service stop, pre-benefit stoploss and existing refund contradiction.
- `SessionAcceptanceTest`: 17 actual-MySQL scenarios covering A20–A32/A36, resource reuse while REVIEW, immutable conflict facts, two-person approval, unknown replay, stale proposal rejection and financial DIFF preservation.
- `python3 -m unittest discover -s simulators -p 'test_device_acceptance.py' -v`: 10 independent device contract tests, including expired/rejected start fencing, callback/ack loss, real boundaries, range replay, append-only correction and concurrent autonomous stop.

Run the MySQL test against a dedicated database, never a production or shared user database. Configure `CHARGEHUB_METER_TEST_JDBC_URL`, `CHARGEHUB_METER_TEST_DB_USER`, and `CHARGEHUB_METER_TEST_DB_PASSWORD`. User/password may fall back to the corresponding `CHARGEHUB_WORKFLOW_DB_*` variables. Flyway applies the full application migration set. The profile is `test`: workers and development business seeds are disabled; each scenario creates unique isolated users, tariffs and connectors and controls its business clock. A missing JDBC environment skips the suite and is not a passing acceptance result.

Source review and license decisions: [SteVe and mall review](../docs/references/device-review.md). The implementation is independent; no GPL source was copied. It implements the project's signed HTTP simulation contract and does not claim OCPP certification.
