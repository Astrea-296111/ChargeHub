package com.chargehub.charging;

import com.chargehub.benefit.BenefitService;
import com.chargehub.infrastructure.BusinessException;
import com.chargehub.infrastructure.Db;
import com.chargehub.infrastructure.Json;
import com.chargehub.infrastructure.Outbox;
import com.chargehub.payment.PaymentService;
import com.chargehub.station.BillingCalculator;
import com.chargehub.station.TariffRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Device facts are append-only. Only a complete, consistent sequence can produce a bill. */
@Service
public class SessionService {
    private final Db db;
    private final BenefitService benefits;
    private final PaymentService payments;
    private final Outbox outbox;
    private final Clock clock;

    public SessionService(Db db, BenefitService benefits, PaymentService payments, Outbox outbox, Clock clock) {
        this.db = db; this.benefits = benefits; this.payments = payments; this.outbox = outbox; this.clock = clock;
    }

    /** Only this explicit rejection commits its raw evidence while returning a forbidden response. */
    public static class DeviceEventRejected extends BusinessException {
        public DeviceEventRejected(String code) { super(403, code, "设备事件与已登记的设备、连接器或会话不匹配"); }
    }

    @Transactional(noRollbackFor = DeviceEventRejected.class)
    public void ingest(Map<String, Object> event) {
        Event value = Event.parse(event);
        var sessionHint = value.sessionNo == null ? null : db.one("SELECT * FROM charge_session WHERE session_no=?", value.sessionNo);
        Map<String, Object> order = null;
        Map<String, Object> session = null;
        if (sessionHint != null) {
            order = lockOrder(Db.str(sessionHint, "order_no"));
            session = db.one("SELECT * FROM charge_session WHERE session_no=? FOR UPDATE", value.sessionNo);
        }
        // Known-session processing always takes the same user/order locks as order operations.
        var connector = db.one("SELECT * FROM connector WHERE id=? FOR UPDATE", value.connectorId);
        var previous = db.one("SELECT * FROM device_event WHERE device_id=? AND event_id=? FOR UPDATE", value.deviceId, value.eventId);
        String hash = Json.hash(Json.write(new TreeMap<>(event)));
        if (previous != null) {
            if (!hash.equals(Db.str(previous, "payload_hash"))) {
                db.update("UPDATE device_event SET process_status='REVIEW',error_code='EVENT_ID_CONFLICT' WHERE id=?", Db.num(previous, "id"));
                db.insert("INSERT INTO ops_audit(operator_id,action,target_type,target_no,reason,before_json,after_json,request_id) VALUES(0,'DEVICE_EVENT_CONFLICT','DeviceEvent',?,'同一设备事件号携带不同内容',?,?,?)",
                        value.eventId, Db.str(previous, "payload"), Json.write(event), UUID.randomUUID().toString());
                // A forged event for another session cannot change that session's business state.
                var originalSession = db.one("SELECT order_no FROM charge_session WHERE session_no=?", Db.str(previous, "session_no"));
                if (originalSession != null && order != null && Objects.equals(Db.str(originalSession, "order_no"), Db.str(order, "order_no"))) {
                    reviewOrder(order, "EVENT_ID_CONFLICT");
                }
            }
            return;
        }
        long eventId = db.insert("INSERT INTO device_event(device_id,event_id,session_no,event_type,seq_no,payload_hash,payload,occurred_at) VALUES(?,?,?,?,?,?,?,?)",
                value.deviceId, value.eventId, value.sessionNo, value.type, value.seq, hash, Json.write(event), value.at);
        if (connector == null || !value.deviceId.equals(Db.str(connector, "device_id"))) {
            reject(eventId, "DEVICE_CONNECTOR_MISMATCH");
        }
        if (value.sessionNo != null && (session == null || order == null || !value.deviceId.equals(Db.str(session, "device_id"))
                || value.connectorId != Db.num(session, "connector_id") || value.connectorId != Db.num(order, "connector_id"))) {
            reject(eventId, "SESSION_OWNERSHIP_MISMATCH");
        }
        if (value.type.equals("STATUS")) {
            // IDLE alone is not an authoritative end-of-session fact and never releases a binding.
            db.update("UPDATE connector SET last_seen_at=?,status=CASE WHEN active_order_no IS NULL THEN ? ELSE status END WHERE id=?",
                    clock.instant(), value.deviceStatus, value.connectorId);
            processed(eventId);
            return;
        }
        if (session == null || order == null || !value.deviceId.equals(Db.str(session, "device_id"))
                || value.connectorId != Db.num(session, "connector_id") || value.connectorId != Db.num(order, "connector_id")) {
            reject(eventId, "SESSION_OWNERSHIP_MISMATCH");
        }
        db.update("UPDATE connector SET last_seen_at=? WHERE id=?", clock.instant(), value.connectorId);
        String orderNo = Db.str(order, "order_no");
        var sample = db.one("SELECT * FROM meter_sample WHERE session_no=? AND seq_no=?", value.sessionNo, value.seq);
        if (sample != null) {
            if (value.wh != Db.num(sample, "meter_wh") || !value.at.equals(Db.instant(sample, "sampled_at"))
                    || !value.kind.equals(Db.str(sample, "sample_kind"))) {
                anomaly(eventId, order, "SEQUENCE_CONFLICT");
            } else {
                processed(eventId);
                finalizeLocked(order, session);
            }
            return;
        }
        if (db.one("SELECT id FROM charge_bill WHERE order_no=?", orderNo) != null) {
            anomaly(eventId, order, "LATE_SAMPLE_AFTER_BILL");
            return;
        }
        if (session.get("final_seq") != null && value.seq > Db.num(session, "final_seq")) {
            anomaly(eventId, order, "SEQUENCE_AFTER_END"); return;
        }
        if (value.type.equals("STOPPED") && session.get("final_seq") != null
                && value.finalSeq != Db.num(session, "final_seq")) {
            anomaly(eventId, order, "FINAL_SEQUENCE_CONFLICT"); return;
        }
        var lower = db.one("SELECT * FROM meter_sample WHERE session_no=? AND seq_no<? ORDER BY seq_no DESC LIMIT 1", value.sessionNo, value.seq);
        var upper = db.one("SELECT * FROM meter_sample WHERE session_no=? AND seq_no>? ORDER BY seq_no LIMIT 1", value.sessionNo, value.seq);
        if ((lower != null && !ordered(Db.instant(lower, "sampled_at"), Db.num(lower, "meter_wh"), value.at, value.wh))
                || (upper != null && !ordered(value.at, value.wh, Db.instant(upper, "sampled_at"), Db.num(upper, "meter_wh")))) {
            anomaly(eventId, order, "METER_ROLLBACK_OR_TIME_CONFLICT"); return;
        }
        if (value.type.equals("STOPPED") && upper != null) {
            anomaly(eventId, order, "FINAL_SEQUENCE_CONFLICT"); return;
        }
        db.insert("INSERT INTO meter_sample(session_no,device_event_id,seq_no,meter_wh,sampled_at,sample_kind) VALUES(?,?,?,?,?,?)",
                value.sessionNo, eventId, value.seq, value.wh, value.at, value.kind);
        if (value.type.equals("STARTED")) {
            db.update("UPDATE charge_session SET start_meter_wh=?,started_at=?,status=CASE WHEN final_seq IS NULL THEN 'CHARGING' ELSE status END WHERE id=?",
                    value.wh, value.at, Db.num(session, "id"));
            String state = Db.str(order, "status");
            if (Set.of("CANCELING", "CANCELED").contains(state)) {
                db.update("UPDATE charge_order SET status='EXCEPTION',exception_code='UNEXPECTED_START',started_at=?,version=version+1 WHERE order_no=?", value.at, orderNo);
                requestStop(order, session);
            } else if (session.get("final_seq") == null && Set.of("START_PENDING", "EXCEPTION").contains(state)) {
                db.update("UPDATE charge_order SET status='CHARGING',started_at=?,exception_code=NULL,version=version+1 WHERE order_no=?", value.at, orderNo);
                db.update("UPDATE connector SET status='CHARGING' WHERE id=? AND active_order_no=?", value.connectorId, orderNo);
            } else {
                db.update("UPDATE charge_order SET started_at=COALESCE(started_at,?) WHERE order_no=?", value.at, orderNo);
            }
        } else if (value.type.equals("STOPPED")) {
            db.update("UPDATE charge_session SET status='STOPPED',end_meter_wh=?,final_seq=?,ended_at=? WHERE id=?",
                    value.wh, value.finalSeq, value.at, Db.num(session, "id"));
            db.update("UPDATE charge_order SET status='BILLING',ended_at=?,version=version+1 WHERE order_no=?", value.at, orderNo);
            outbox.enqueue("SessionStopped", orderNo, Map.of("orderNo", orderNo, "sessionNo", value.sessionNo, "finalSeq", value.finalSeq));
        }
        processed(eventId);
        session = db.one("SELECT * FROM charge_session WHERE session_no=?", value.sessionNo);
        finalizeLocked(order, session);
    }

    private void reject(long eventId, String code) {
        db.update("UPDATE device_event SET process_status='REVIEW',error_code=? WHERE id=?", code, eventId);
        throw new DeviceEventRejected(code);
    }

    private void processed(long id) { db.update("UPDATE device_event SET process_status='PROCESSED',error_code=NULL WHERE id=?", id); }

    private void anomaly(long eventId, Map<String, Object> order, String code) {
        db.update("UPDATE device_event SET process_status='REVIEW',error_code=? WHERE id=?", code, eventId);
        reviewOrder(order, code);
    }

    private void reviewOrder(Map<String, Object> order, String code) {
        // Anomalies can never rewrite an issued bill or silently reopen its financial lifecycle.
        db.update("UPDATE charge_order SET status=CASE WHEN status IN ('COMPLETED','CANCELED') THEN status ELSE 'REVIEW' END,exception_code=?,version=version+1 WHERE order_no=?",
                code, Db.str(order, "order_no"));
    }

    private Map<String, Object> lockOrder(String orderNo) {
        var hint = db.one("SELECT user_id FROM charge_order WHERE order_no=?", orderNo);
        if (hint == null) return null;
        db.one("SELECT id FROM iam_user WHERE id=? FOR UPDATE", Db.num(hint, "user_id"));
        return db.one("SELECT * FROM charge_order WHERE order_no=? FOR UPDATE", orderNo);
    }

    private void requestStop(Map<String, Object> order, Map<String, Object> session) {
        String orderNo = Db.str(order, "order_no");
        var command = db.one("SELECT * FROM device_command WHERE order_no=? AND command_type='STOP'", orderNo);
        String commandId = command == null ? UUID.randomUUID().toString() : Db.str(command, "command_id");
        var payload = Map.<String, Object>of("orderNo", orderNo, "commandId", commandId,
                "sessionNo", Db.str(session, "session_no"), "connectorId", Long.toString(Db.num(session, "connector_id")), "deviceId", Db.str(session, "device_id"));
        if (command == null) {
            db.insert("INSERT INTO device_command(command_id,order_no,device_id,command_type,request_payload,expires_at) VALUES(?,?,?,'STOP',?,?)",
                    commandId, orderNo, Db.str(session, "device_id"), Json.write(payload), clock.instant().plusSeconds(300));
        }
        outbox.enqueue("ChargeStopRequested", orderNo, payload);
    }

    /** Platform backstop: reserve a 5% stop margin using charges before any benefits. */
    @Transactional
    public void enforcePrepaid(String orderNo) {
        var order = lockOrder(orderNo);
        if (order == null || !Set.of("CHARGING", "REVIEW", "EXCEPTION").contains(Db.str(order, "status"))) return;
        var session = db.one("SELECT * FROM charge_session WHERE order_no=? FOR UPDATE", orderNo);
        if (session == null || !"CHARGING".equals(Db.str(session, "status"))) return;
        var samples = db.rows("SELECT * FROM meter_sample WHERE session_no=? ORDER BY seq_no", Db.str(session, "session_no"));
        String reason;
        try {
            if ("REVIEW".equals(Db.str(order, "status")) || db.one("SELECT id FROM device_event WHERE session_no=? AND process_status='REVIEW' LIMIT 1", Db.str(session, "session_no")) != null) {
                throw new MeteringProblem("METERING_UNCERTAIN");
            }
            if (samples.size() == 1 && Db.num(samples.getFirst(), "seq_no") == 0
                    && "START".equals(Db.str(samples.getFirst(), "sample_kind"))) return;
            if (samples.size() < 2) throw new MeteringProblem("METERING_UNCERTAIN");
            // This endpoint exists only in memory for stoploss estimation; original samples stay immutable.
            var estimateSamples = new ArrayList<>(samples);
            var endpoint = new HashMap<>(estimateSamples.getLast());
            endpoint.put("sample_kind", "END");
            estimateSamples.set(estimateSamples.size() - 1, endpoint);
            var priced = segments(Json.read(Db.str(order, "tariff_snapshot")), estimateSamples);
            var estimate = BillingCalculator.calculate(priced.stream().map(BillSegment::pricing).toList(), false, null, null, Db.num(order, "prepaid_fen"));
            long beforeBenefits = Math.addExact(estimate.energyFen(), estimate.serviceFen());
            if (beforeBenefits < Db.num(order, "prepaid_fen") * 95 / 100) return;
            reason = "PREPAID_STOPLOSS_95_PERCENT";
        } catch (MeteringProblem | BusinessException | DateTimeException | ArithmeticException | IllegalArgumentException problem) {
            // Missing boundaries cannot be interpolated into a bill. Stop safely and collect the facts.
            reason = "PREPAID_METER_UNCERTAIN";
        }
        String originalReason = Db.str(order, "exception_code");
        String visibleReason = Set.of("REVIEW", "EXCEPTION").contains(Db.str(order, "status")) && originalReason != null ? originalReason : reason;
        db.update("UPDATE charge_order SET status='STOP_PENDING',exception_code=?,version=version+1 WHERE order_no=?", visibleReason, orderNo);
        requestStop(order, session);
    }

    @Transactional
    public void tryFinalize(String orderNo) {
        var order = lockOrder(orderNo);
        if (order == null) throw new BusinessException(404, "ORDER_NOT_FOUND", "订单不存在");
        var session = db.one("SELECT * FROM charge_session WHERE order_no=? FOR UPDATE", orderNo);
        finalizeLocked(order, session);
    }

    private void finalizeLocked(Map<String, Object> order, Map<String, Object> session) {
        if (session == null || !"STOPPED".equals(Db.str(session, "status"))) return;
        String orderNo = Db.str(order, "order_no");
        if (db.one("SELECT id FROM charge_bill WHERE order_no=?", orderNo) != null) return;
        if (db.one("SELECT id FROM device_event WHERE session_no=? AND process_status='REVIEW' LIMIT 1", Db.str(session, "session_no")) != null) {
            reviewOrder(order, "METER_REVIEW_REQUIRED"); return;
        }
        List<BillSegment> segments;
        try {
            var samples = db.rows("SELECT * FROM meter_sample WHERE session_no=? ORDER BY seq_no", Db.str(session, "session_no"));
            if (session.get("final_seq") == null || Db.num(session, "final_seq") != (long) samples.size() - 1) {
                throw new MeteringProblem("MISSING_SEQUENCE");
            }
            segments = segments(Json.read(Db.str(order, "tariff_snapshot")), samples);
            if (Db.num(session, "end_meter_wh") != Db.num(samples.getLast(), "meter_wh")) throw new MeteringProblem("END_METER_CONFLICT");
        } catch (MeteringProblem problem) {
            reviewOrder(order, problem.code); return;
        } catch (BusinessException | DateTimeException problem) {
            reviewOrder(order, "INVALID_TARIFF_SNAPSHOT"); return;
        }
        var reservation = db.one("SELECT * FROM benefit_reservation WHERE order_no=?", orderNo);
        boolean member = reservation != null && "RESERVED".equals(Db.str(reservation, "member_state"));
        Map<String, Object> coupon = reservation != null && "LOCKED".equals(Db.str(reservation, "coupon_state"))
                ? Json.read(Db.str(reservation, "coupon_rule_snapshot")) : null;
        BillingCalculator.Result result;
        try {
            result = BillingCalculator.calculate(segments.stream().map(BillSegment::pricing).toList(), member,
                    coupon == null ? null : Db.num(coupon, "thresholdFen"), coupon == null ? null : Db.num(coupon, "discountFen"), Db.num(order, "prepaid_fen"));
        } catch (ArithmeticException | IllegalArgumentException problem) {
            reviewOrder(order, "BILLING_AMOUNT_OVERFLOW_OR_RULE_INVALID"); return;
        }
        // A contradictory late session must not generate another refund after a full cancellation.
        var priorFullRefund = db.one("SELECT * FROM refund_order WHERE order_no=? AND reason='FULL_CANCEL'", orderNo);
        boolean refundContradiction = result.payableFen() > 0 && priorFullRefund != null;
        var tariff = Json.read(Db.str(order, "tariff_snapshot"));
        String billNo = UUID.randomUUID().toString();
        long billId = db.insert("INSERT INTO charge_bill(bill_no,order_no,total_wh,energy_fen,service_fen,member_discount_fen,coupon_discount_fen,payable_fen,rule_hash) VALUES(?,?,?,?,?,?,?,?,?)",
                billNo, orderNo, result.totalWh(), result.energyFen(), result.serviceFen(), result.memberDiscountFen(), result.couponDiscountFen(), result.payableFen(), tariff.get("ruleHash"));
        int number = 0;
        for (BillSegment segment : segments) {
            db.insert("INSERT INTO charge_bill_item(bill_id,segment_no,segment_start,segment_end,delta_wh,energy_rate_fen_per_kwh,service_rate_fen_per_kwh,energy_fen,service_fen) VALUES(?,?,?,?,?,?,?,?,?)",
                    billId, ++number, segment.start, segment.end, segment.wh, segment.energyRate, segment.serviceRate,
                    BillingCalculator.componentFen(segment.wh, segment.energyRate), BillingCalculator.componentFen(segment.wh, segment.serviceRate));
        }
        benefits.finalizeReservation(orderNo, result.memberDiscountFen(), result.couponDiscountFen());
        String financial = refundContradiction ? "DIFF" : result.shortfallFen() > 0 ? "SHORTFALL" : result.refundFen() > 0 ? "REFUNDING" : "CLOSED";
        db.update("UPDATE charge_order SET status=?,financial_status=?,exception_code=?,version=version+1 WHERE order_no=?",
                result.totalWh() == 0 ? "CANCELED" : "COMPLETED", financial,
                refundContradiction ? "REFUND_SERVICE_CONTRADICTION" : result.shortfallFen() > 0 ? "PREPAID_SHORTFALL" : null, orderNo);
        if (result.refundFen() > 0 && !refundContradiction) {
            var refund = payments.ensureRefund(orderNo, result.refundFen(), result.totalWh() == 0 ? "FULL_CANCEL" : "BALANCE");
            outbox.enqueue("RefundRequested", Db.str(refund, "refund_no"), Map.of("refundNo", Db.str(refund, "refund_no"), "orderNo", orderNo));
        }
        outbox.enqueue("BillFinalized", orderNo, Map.of("orderNo", orderNo, "billNo", billNo, "payableFen", result.payableFen(), "refundFen", result.refundFen()));
        // The STOPPED event has proved the session ended. Conditional updates preserve newer bindings.
        db.update("UPDATE connector SET active_order_no=NULL,status='IDLE',version=version+1 WHERE id=? AND active_order_no=?", Db.num(order, "connector_id"), orderNo);
        db.update("UPDATE iam_user SET active_order_no=NULL WHERE id=? AND active_order_no=?", Db.num(order, "user_id"), orderNo);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> bill(String orderNo) {
        var row = db.one("SELECT * FROM charge_bill WHERE order_no=?", orderNo);
        if (row == null) throw new BusinessException(409, "BILL_NOT_READY", "计量未完成校验，正式账单尚未生成");
        var result = new LinkedHashMap<String, Object>();
        result.put("billNo", Db.str(row, "bill_no")); result.put("orderNo", orderNo);
        var amounts = new LinkedHashMap<String, Object>();
        for (String[] key : new String[][] {{"total_wh", "totalWh"}, {"energy_fen", "energyFen"}, {"service_fen", "serviceFen"},
                {"member_discount_fen", "memberDiscountFen"}, {"coupon_discount_fen", "couponDiscountFen"}, {"payable_fen", "payableFen"}}) amounts.put(key[1], Db.num(row, key[0]));
        result.put("amounts", amounts); result.put("createdAt", Db.instant(row, "created_at"));
        result.put("items", db.rows("SELECT * FROM charge_bill_item WHERE bill_id=? ORDER BY segment_no", Db.num(row, "id")).stream().map(item -> Map.of(
                "segmentNo", Db.num(item, "segment_no"), "start", Db.instant(item, "segment_start"), "end", Db.instant(item, "segment_end"),
                "deltaWh", Db.num(item, "delta_wh"), "energyRateFenPerKwh", Db.num(item, "energy_rate_fen_per_kwh"), "serviceRateFenPerKwh", Db.num(item, "service_rate_fen_per_kwh"),
                "energyFen", Db.num(item, "energy_fen"), "serviceFen", Db.num(item, "service_fen"))).toList());
        return result;
    }

    record BillSegment(Instant start, Instant end, long wh, int energyRate, int serviceRate) {
        BillingCalculator.Segment pricing() { return new BillingCalculator.Segment(wh, energyRate, serviceRate); }
    }

    static class MeteringProblem extends RuntimeException {
        final String code;
        MeteringProblem(String code) { super(code); this.code = code; }
    }

    /** Normal samples validate the sequence; only required tariff/day boundaries divide billing. */
    static List<BillSegment> segments(Map<String, Object> tariff, List<Map<String, Object>> samples) {
        if (samples.size() < 2) throw new MeteringProblem("MISSING_ENDPOINT");
        var slots = TariffRules.canonicalSlots(tariff.get("slots"), true);
        ZoneId zone = ZoneId.of(String.valueOf(tariff.getOrDefault("timezone", "Asia/Shanghai")));
        var first = samples.getFirst(); var last = samples.getLast();
        if (!"START".equals(Db.str(first, "sample_kind")) || !"END".equals(Db.str(last, "sample_kind"))) throw new MeteringProblem("MISSING_ENDPOINT");
        Instant start = Db.instant(first, "sampled_at"), end = Db.instant(last, "sampled_at");
        if (end.isBefore(start)) throw new MeteringProblem("INVALID_SESSION_TIME");
        NavigableMap<Instant, Map<String, Object>> byTime = new TreeMap<>();
        long previousWh = -1;
        Instant previousAt = null;
        for (int i = 0; i < samples.size(); i++) {
            var sample = samples.get(i);
            Instant at = Db.instant(sample, "sampled_at"); long wh = Db.num(sample, "meter_wh");
            if (Db.num(sample, "seq_no") != i) throw new MeteringProblem("MISSING_SEQUENCE");
            if (wh < 0 || wh < previousWh) throw new MeteringProblem("METER_ROLLBACK");
            if (at == null || previousAt != null && !ordered(previousAt, previousWh, at, wh)) throw new MeteringProblem("INVALID_SAMPLE_TIME");
            // Equal-time, equal-meter samples are possible at database millisecond precision.
            // Keep boundary proof when an ordinary duplicate has the same timestamp.
            byTime.merge(at, sample, (old, current) -> Db.str(old, "sample_kind").endsWith("BOUNDARY") ? old : current);
            previousWh = wh; previousAt = at;
        }
        if (start.equals(end)) return List.of(); // Proven zero service: bill zero, no invalid zero-length item.
        SortedSet<Instant> boundaries = new TreeSet<>(); boundaries.add(start); boundaries.add(end);
        for (LocalDate day = start.atZone(zone).toLocalDate(); !day.isAfter(end.atZone(zone).toLocalDate()); day = day.plusDays(1)) {
            for (var slot : slots) {
                int minute = ((Number) slot.get("startMinute")).intValue();
                Instant boundary = day.atStartOfDay(zone).plusMinutes(minute).toInstant();
                if (boundary.isAfter(start) && boundary.isBefore(end)) {
                    var sample = byTime.get(boundary);
                    String expectedKind = minute == 0 ? "DAY_BOUNDARY" : "TARIFF_BOUNDARY";
                    if (sample == null || !expectedKind.equals(Db.str(sample, "sample_kind"))) throw new MeteringProblem("MISSING_TARIFF_BOUNDARY");
                    boundaries.add(boundary);
                }
            }
        }
        var points = new ArrayList<>(boundaries);
        List<BillSegment> result = new ArrayList<>();
        for (int i = 1; i < points.size(); i++) {
            Instant a = points.get(i - 1), b = points.get(i);
            int minute = a.atZone(zone).getHour() * 60 + a.atZone(zone).getMinute();
            var rate = slots.stream().filter(slot -> ((Number) slot.get("startMinute")).intValue() <= minute
                    && minute < ((Number) slot.get("endMinute")).intValue()).findFirst().orElseThrow(() -> new MeteringProblem("INVALID_TARIFF_SNAPSHOT"));
            result.add(new BillSegment(a, b, Db.num(byTime.get(b), "meter_wh") - Db.num(byTime.get(a), "meter_wh"),
                    ((Number) rate.get("energyRateFenPerKwh")).intValue(), ((Number) rate.get("serviceRateFenPerKwh")).intValue()));
        }
        return List.copyOf(result);
    }

    private static boolean ordered(Instant before, long beforeWh, Instant after, long afterWh) {
        return !after.isBefore(before) && afterWh >= beforeWh && (!after.equals(before) || afterWh == beforeWh);
    }

    private record Event(String eventId, String deviceId, long connectorId, String type, String sessionNo,
                         Long seq, Long wh, Long finalSeq, String kind, Instant at, String deviceStatus) {
        static Event parse(Map<String, Object> input) {
            try {
                var allowed = Set.of("eventId", "deviceId", "connectorId", "eventType", "sessionNo", "seqNo", "meterWh", "finalSeq", "occurredAt", "sampleKind", "deviceStatus");
                if (!allowed.containsAll(input.keySet())) throw new IllegalArgumentException();
                String id = uuid(input.get("eventId")), device = Objects.toString(input.get("deviceId"), "");
                if (!(input.get("deviceId") instanceof String) || device.isBlank() || device.length() > 64 || !(input.get("connectorId") instanceof String)
                        || !Objects.toString(input.get("connectorId"), "").matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
                long connector = Long.parseLong(input.get("connectorId").toString());
                String type = Objects.toString(input.get("eventType"), ""), kind = Objects.toString(input.get("sampleKind"), "");
                // Preserve the exact wire payload for evidence; materialize times at database precision.
                Instant at = Instant.parse(Objects.toString(input.get("occurredAt"), "")).truncatedTo(ChronoUnit.MILLIS);
                String session = input.containsKey("sessionNo") ? uuid(input.get("sessionNo")) : null;
                if (input.containsKey("seqNo")) nonnegative(input.get("seqNo"));
                if (input.containsKey("meterWh")) nonnegative(input.get("meterWh"));
                if (input.containsKey("finalSeq")) nonnegative(input.get("finalSeq"));
                if (input.containsKey("sampleKind") && !Set.of("START", "NORMAL", "TARIFF_BOUNDARY", "DAY_BOUNDARY", "END").contains(kind)) throw new IllegalArgumentException();
                if (input.containsKey("deviceStatus") && !Set.of("IDLE", "CHARGING", "OFFLINE", "FAULT").contains(input.get("deviceStatus"))) throw new IllegalArgumentException();
                if (type.equals("STATUS")) {
                    String status = Objects.toString(input.get("deviceStatus"), "");
                    if (!Set.of("IDLE", "CHARGING", "OFFLINE", "FAULT").contains(status)) throw new IllegalArgumentException();
                    return new Event(id, device, connector, type, session, null, null, null, null, at, status);
                }
                if (session == null) throw new IllegalArgumentException();
                long seq = nonnegative(input.get("seqNo")), wh = nonnegative(input.get("meterWh"));
                Long finalSeq = null;
                switch (type) {
                    case "STARTED" -> { if (seq != 0 || !kind.equals("START")) throw new IllegalArgumentException(); }
                    case "METER" -> { if (seq == 0 || !Set.of("NORMAL", "TARIFF_BOUNDARY", "DAY_BOUNDARY").contains(kind)) throw new IllegalArgumentException(); }
                    case "STOPPED" -> { finalSeq = nonnegative(input.get("finalSeq")); if (seq == 0 || seq != finalSeq || !kind.equals("END")) throw new IllegalArgumentException(); }
                    default -> throw new IllegalArgumentException();
                }
                return new Event(id, device, connector, type, session, seq, wh, finalSeq, kind, at, null);
            } catch (RuntimeException ex) {
                throw new BusinessException(400, "INVALID_DEVICE_EVENT", "设备事件字段、时间、序号或累计电量格式不正确");
            }
        }

        private static String uuid(Object source) {
            String value = Objects.toString(source, "");
            if (!UUID.fromString(value).toString().equals(value.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException();
            return value;
        }
        private static long nonnegative(Object source) {
            if (!(source instanceof Number)) throw new IllegalArgumentException();
            long result = new BigDecimal(source.toString()).longValueExact();
            if (result < 0) throw new IllegalArgumentException();
            return result;
        }
    }
}
