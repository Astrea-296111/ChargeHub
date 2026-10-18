package com.chargehub.charging;

import com.chargehub.benefit.BenefitService;
import com.chargehub.infrastructure.BusinessException;
import com.chargehub.infrastructure.Db;
import com.chargehub.infrastructure.Json;
import com.chargehub.infrastructure.Outbox;
import com.chargehub.payment.PaymentService;
import com.chargehub.station.BillingCalculator;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionServiceTest {
    private static final Instant START = Instant.parse("2026-10-18T00:00:00Z"); // Shanghai 08:00

    @Test
    void ordinarySamplesDoNotIntroduceRoundingSegments() {
        var segments = SessionService.segments(tariff(slot(0, 1440, 100, 100)), List.of(
                sample(0, 1000, START, "START"),
                sample(1, 1005, START.plusSeconds(1), "NORMAL"),
                sample(2, 1010, START.plusSeconds(2), "END")));
        assertEquals(1, segments.size());
        var bill = BillingCalculator.calculate(segments.stream().map(SessionService.BillSegment::pricing).toList(), false, null, null, 10000);
        assertEquals(1, bill.energyFen());
        assertEquals(1, bill.serviceFen());
        assertEquals(2, bill.payableFen()); // Rounding each 5 Wh separately would incorrectly produce 4 fen.
    }

    @Test
    void exactTariffBoundaryUsesCumulativeDifferencesAndFrozenRates() {
        var segments = SessionService.segments(tariff(slot(0, 540, 81, 39), slot(540, 1440, 121, 59)), List.of(
                sample(0, 100000, START, "START"),
                sample(1, 103333, START.plusSeconds(3600), "TARIFF_BOUNDARY"),
                sample(2, 110000, START.plusSeconds(7200), "END")));
        assertEquals(List.of(3333L, 6667L), segments.stream().map(SessionService.BillSegment::wh).toList());
        var bill = BillingCalculator.calculate(segments.stream().map(SessionService.BillSegment::pricing).toList(), false, null, null, 10000);
        assertEquals(1077, bill.energyFen()); assertEquals(523, bill.serviceFen());
    }

    @Test
    void midnightRequiresAnExplicitDayBoundaryEvenWithTheSamePrice() {
        Instant start = Instant.parse("2026-10-18T15:59:00Z");
        Instant midnight = Instant.parse("2026-10-18T16:00:00Z");
        var segments = SessionService.segments(tariff(slot(0, 1440, 80, 50)), List.of(
                sample(0, 1000, start, "START"), sample(1, 2000, midnight, "DAY_BOUNDARY"),
                sample(2, 3000, midnight.plusSeconds(60), "END")));
        assertEquals(2, segments.size());
        assertEquals(midnight, segments.getFirst().end());
        assertEquals(midnight, segments.getLast().start());
        assertProblem("MISSING_TARIFF_BOUNDARY", () -> SessionService.segments(tariff(slot(0, 1440, 80, 50)), List.of(
                sample(0, 1000, start, "START"), sample(1, 3000, midnight.plusSeconds(60), "END"))));
    }

    @Test
    void missingBoundaryIsNeverEstimatedFromNormalSamples() {
        var values = List.of(sample(0, 0, START, "START"), sample(1, 1000, START.plusSeconds(1800), "NORMAL"),
                sample(2, 2000, START.plusSeconds(7200), "END"));
        assertProblem("MISSING_TARIFF_BOUNDARY", () -> SessionService.segments(tariff(slot(0, 540, 80, 50), slot(540, 1440, 100, 60)), values));
    }

    @Test
    void gapsRollbackAndReversedTimesCannotGenerateBills() {
        var rule = tariff(slot(0, 1440, 80, 50));
        assertProblem("MISSING_SEQUENCE", () -> SessionService.segments(rule, List.of(
                sample(0, 100, START, "START"), sample(2, 200, START.plusSeconds(60), "END"))));
        assertProblem("METER_ROLLBACK", () -> SessionService.segments(rule, List.of(
                sample(0, 100, START, "START"), sample(1, 99, START.plusSeconds(60), "END"))));
        assertProblem("INVALID_SAMPLE_TIME", () -> SessionService.segments(rule, List.of(
                sample(0, 100, START, "START"), sample(1, 150, START.plusSeconds(120), "NORMAL"),
                sample(2, 200, START.plusSeconds(60), "END"))));
    }

    @Test
    void sampleAtEndBoundaryDoesNotRequireAnExtraBoundaryEvent() {
        var segments = SessionService.segments(tariff(slot(0, 540, 80, 50), slot(540, 1440, 100, 60)), List.of(
                sample(0, 0, START, "START"), sample(1, 1000, START.plusSeconds(3600), "END")));
        assertEquals(1, segments.size()); assertEquals(80, segments.getFirst().energyRate());
    }

    @Test
    void immediateStopAtSameMillisecondWithSameMeterHasZeroBillAndNoItems() {
        var segments = SessionService.segments(tariff(slot(0, 1440, 80, 50)), List.of(
                sample(0, 1000, START, "START"), sample(1, 1000, START, "END")));
        assertTrue(segments.isEmpty());
        var bill = BillingCalculator.calculate(segments.stream().map(SessionService.BillSegment::pricing).toList(), true, 0L, 300L, 10000);
        assertEquals(0, bill.payableFen()); assertEquals(10000, bill.refundFen());
        assertFalse(bill.memberConsumed()); assertFalse(bill.couponUsed());
        assertProblem("INVALID_SAMPLE_TIME", () -> SessionService.segments(tariff(slot(0, 1440, 80, 50)), List.of(
                sample(0, 1000, START, "START"), sample(1, 1001, START, "END"))));
    }

    @Test
    void billResponseUsesThePublishedAmountsObject() {
        Db db = mock(Db.class);
        String no = UUID.randomUUID().toString();
        when(db.one(startsWith("SELECT * FROM charge_bill "), any(Object[].class))).thenReturn(Map.of(
                "id", 1L, "bill_no", UUID.randomUUID().toString(), "total_wh", 20000L, "payable_fen", 2600L, "created_at", START));
        when(db.rows(startsWith("SELECT * FROM charge_bill_item"), any(Object[].class))).thenReturn(List.of());
        var service = new SessionService(db, mock(BenefitService.class), mock(PaymentService.class), mock(Outbox.class), Clock.systemUTC());
        var response = service.bill(no);
        assertEquals(java.util.Set.of("billNo", "orderNo", "amounts", "items", "createdAt"), response.keySet());
        assertEquals(2600L, ((Map<?, ?>) response.get("amounts")).get("payableFen"));
    }

    @Test
    void malformedDeviceEventsAreRejectedBeforeAnyDatabaseEffects() {
        Db db = mock(Db.class);
        var service = new SessionService(db, mock(BenefitService.class), mock(PaymentService.class), mock(Outbox.class), Clock.systemUTC());
        var event = new java.util.HashMap<String, Object>(Map.of("eventId", UUID.randomUUID().toString(), "deviceId", "dev-1", "connectorId", "1",
                "eventType", "STOPPED", "sessionNo", UUID.randomUUID().toString(), "seqNo", 3, "finalSeq", 2, "meterWh", 100,
                "sampleKind", "END", "occurredAt", START.toString()));
        var failure = assertThrows(BusinessException.class, () -> service.ingest(event));
        assertEquals("INVALID_DEVICE_EVENT", failure.code()); verifyNoInteractions(db);
        event.put("finalSeq", 3); event.put("meterWh", -1);
        assertThrows(BusinessException.class, () -> service.ingest(event)); verifyNoInteractions(db);
    }

    @Test
    void serviceAfterFullCancellationIsAFinancialDifferenceRatherThanAnotherRefund() {
        Db db = mock(Db.class); PaymentService payments = mock(PaymentService.class);
        BenefitService benefits = mock(BenefitService.class); Outbox outbox = mock(Outbox.class);
        String no = UUID.randomUUID().toString(), sessionNo = UUID.randomUUID().toString();
        var order = Map.<String, Object>of("order_no", no, "user_id", 1L, "connector_id", 2L, "prepaid_fen", 10000L,
                "status", "CANCELED", "tariff_snapshot", Json.write(tariff(slot(0, 1440, 80, 50))));
        var session = Map.<String, Object>of("session_no", sessionNo, "order_no", no, "status", "STOPPED", "final_seq", 1L, "end_meter_wh", 21000L);
        when(db.one(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("SELECT user_id")) return Map.of("user_id", 1L);
            if (sql.startsWith("SELECT * FROM charge_order")) return order;
            if (sql.startsWith("SELECT * FROM charge_session")) return session;
            if (sql.startsWith("SELECT * FROM refund_order")) return Map.of("reason", "FULL_CANCEL", "amount_fen", 10000L);
            return null;
        });
        when(db.rows(startsWith("SELECT * FROM meter_sample"), any(Object[].class))).thenReturn(List.of(
                sample(0, 1000, START, "START"), sample(1, 21000, START.plusSeconds(60), "END")));
        var service = new SessionService(db, benefits, payments, outbox, Clock.systemUTC());
        service.tryFinalize(no);
        verifyNoInteractions(payments); // The existing full refund cannot be followed by another balance refund.
        verify(db).insert(startsWith("INSERT INTO charge_bill("), anyString(), eq(no), eq(20000L), eq(1600L), eq(1000L), eq(0L), eq(0L), eq(2600L), any());
        verify(db).update(startsWith("UPDATE charge_order SET status=?"), eq("COMPLETED"), eq("DIFF"), eq("REFUND_SERVICE_CONTRADICTION"), eq(no));
        verify(benefits).finalizeReservation(no, 0L, 0L);
    }

    @Test
    void prepaidBackstopStopsAtRaw9500EvenThoughBenefitsWouldReducePayableTo8900() {
        var discounted = BillingCalculator.calculate(List.of(new BillingCalculator.Segment(47500, 100, 100)), true, 500L, 300L, 10000);
        assertEquals(8900, discounted.payableFen());
        var samples = List.of(sample(0, 100000, START, "START"), sample(1, 147500, START.plusSeconds(60), "NORMAL"));
        var fixture = prepaidFixture(samples);
        fixture.service.enforcePrepaid(fixture.orderNo);
        verify(fixture.db).update(startsWith("UPDATE charge_order SET status='STOP_PENDING'"), eq("PREPAID_STOPLOSS_95_PERCENT"), eq(fixture.orderNo));
        verify(fixture.db).insert(startsWith("INSERT INTO device_command"), anyString(), eq(fixture.orderNo), eq("device-1"), anyString(), any(Instant.class));
        verify(fixture.outbox).enqueue(eq("ChargeStopRequested"), eq(fixture.orderNo), anyMap());
        assertEquals("NORMAL", samples.getLast().get("sample_kind")); // Estimation must not forge a persisted END sample.
        verifyNoInteractions(fixture.payments, fixture.benefits);
    }

    @Test
    void prepaidBackstopLeavesCertifiedChargesBelowMarginRunning() {
        var fixture = prepaidFixture(List.of(sample(0, 100000, START, "START"), sample(1, 147490, START.plusSeconds(60), "NORMAL")));
        fixture.service.enforcePrepaid(fixture.orderNo);
        verify(fixture.db, never()).update(anyString(), any(Object[].class));
        verifyNoInteractions(fixture.outbox, fixture.payments, fixture.benefits);
    }

    @Test
    void prepaidBackstopConservativelyStopsOnAnUncertifiedMeteringGap() {
        var fixture = prepaidFixture(List.of(sample(0, 100000, START, "START"), sample(2, 100100, START.plusSeconds(60), "NORMAL")));
        fixture.service.enforcePrepaid(fixture.orderNo);
        verify(fixture.db).update(startsWith("UPDATE charge_order SET status='STOP_PENDING'"), eq("PREPAID_METER_UNCERTAIN"), eq(fixture.orderNo));
        verify(fixture.outbox).enqueue(eq("ChargeStopRequested"), eq(fixture.orderNo), anyMap());
        verify(fixture.db, never()).insert(startsWith("INSERT INTO charge_bill("), any(Object[].class));
    }

    @Test
    void prepaidBackstopIncludesActiveReviewAndExceptionWithoutHidingTheirReason() {
        for (String state : List.of("REVIEW", "EXCEPTION")) {
            var fixture = prepaidFixture(List.of(sample(0, 100000, START, "START"), sample(2, 100100, START.plusSeconds(60), "NORMAL")),
                    state, "METER_ROLLBACK_OR_TIME_CONFLICT");
            fixture.service.enforcePrepaid(fixture.orderNo);
            verify(fixture.db).update(startsWith("UPDATE charge_order SET status='STOP_PENDING'"), eq("METER_ROLLBACK_OR_TIME_CONFLICT"), eq(fixture.orderNo));
            verify(fixture.outbox).enqueue(eq("ChargeStopRequested"), eq(fixture.orderNo), anyMap());
        }
    }

    @Test
    void prepaidBackstopNeverCreatesAStopForAnAlreadyEndedSession() {
        var fixture = prepaidFixture(List.of(sample(0, 100000, START, "START"), sample(1, 200000, START.plusSeconds(60), "NORMAL")));
        when(fixture.db.one(eq("SELECT * FROM charge_session WHERE order_no=? FOR UPDATE"), eq(fixture.orderNo))).thenReturn(Map.of("status", "STOPPED"));
        fixture.service.enforcePrepaid(fixture.orderNo);
        verify(fixture.db, never()).update(anyString(), any(Object[].class));
        verifyNoInteractions(fixture.outbox, fixture.payments, fixture.benefits);
    }

    private record PrepaidFixture(Db db, BenefitService benefits, PaymentService payments, Outbox outbox,
                                  SessionService service, String orderNo) {}

    private static PrepaidFixture prepaidFixture(List<Map<String, Object>> samples) {
        return prepaidFixture(samples, "CHARGING", null);
    }

    private static PrepaidFixture prepaidFixture(List<Map<String, Object>> samples, String state, String exception) {
        Db db = mock(Db.class); var benefits = mock(BenefitService.class); var payments = mock(PaymentService.class); var outbox = mock(Outbox.class);
        String no = UUID.randomUUID().toString();
        var order = new java.util.HashMap<>(Map.<String, Object>of("order_no", no, "user_id", 1L, "status", state, "prepaid_fen", 10000L,
                "member_rule_snapshot", Json.write(Map.of("discountBasisPoints", 2000, "maxDiscountFen", 300)),
                "tariff_snapshot", Json.write(tariff(slot(0, 1440, 100, 100)))));
        if (exception != null) order.put("exception_code", exception);
        var session = Map.<String, Object>of("session_no", UUID.randomUUID().toString(), "status", "CHARGING", "device_id", "device-1", "connector_id", 1L);
        when(db.one(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("SELECT user_id")) return Map.of("user_id", 1L);
            if (sql.startsWith("SELECT * FROM charge_order")) return order;
            if (sql.startsWith("SELECT * FROM charge_session")) return session;
            return null;
        });
        when(db.rows(startsWith("SELECT * FROM meter_sample"), any(Object[].class))).thenReturn(samples);
        return new PrepaidFixture(db, benefits, payments, outbox,
                new SessionService(db, benefits, payments, outbox, Clock.systemUTC()), no);
    }

    private static void assertProblem(String code, org.junit.jupiter.api.function.Executable work) {
        assertEquals(code, assertThrows(SessionService.MeteringProblem.class, work).code);
    }

    @SafeVarargs
    private static Map<String, Object> tariff(Map<String, Object>... slots) {
        return Map.of("timezone", "Asia/Shanghai", "slots", List.of(slots), "ruleHash", "0".repeat(64));
    }

    private static Map<String, Object> slot(int start, int end, int energy, int service) {
        return Map.of("startMinute", start, "endMinute", end, "energyRateFenPerKwh", energy, "serviceRateFenPerKwh", service);
    }

    private static Map<String, Object> sample(long seq, long wh, Instant at, String kind) {
        return Map.of("seq_no", seq, "meter_wh", wh, "sampled_at", at, "sample_kind", kind);
    }
}
