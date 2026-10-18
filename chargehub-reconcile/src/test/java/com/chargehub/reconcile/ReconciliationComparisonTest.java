package com.chargehub.reconcile;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ReconciliationComparisonTest {
    private final ChannelCsv.Export export = ChannelCsvTest.parse(ChannelCsvTest.CSV);
    private final ChannelCsv.Row row = export.rows().getFirst();

    private Map<String, Object> expected() {
        return new HashMap<>(Map.of("pay_no", row.payNo(), "channel", "MOCK", "currency", "CNY", "amount_fen", 10000L));
    }

    private Map<String, Object> fact() {
        return new HashMap<>(Map.of("biz_no", row.payNo(), "provider_biz_no", row.providerBizNo(), "currency", "CNY",
                "amount_fen", 10000L, "occurred_at", Instant.parse("2026-10-18T07:00:00Z")));
    }

    @Test void exactMoneyAndBusinessAndDateMatch() {
        assertNull(ReconciliationService.compare(export, row, fact(), expected()));
    }

    @Test void channelFactWithMissingLocalCaptureIsRecoverableEvidence() {
        assertEquals("CHANNEL_ONLY", ReconciliationService.compare(export, row, null, expected()));
        assertEquals("BUSINESS_MISMATCH", ReconciliationService.compare(export, row, null, null));
    }

    @Test void expectedAmountAndCurrencyAreCheckedEvenWithoutLocalMoney() {
        var expected = expected();
        expected.put("amount_fen", 9000L);
        assertEquals("AMOUNT_MISMATCH", ReconciliationService.compare(export, row, null, expected));
        expected = expected(); expected.put("currency", "USD");
        assertEquals("CURRENCY_MISMATCH", ReconciliationService.compare(export, row, null, expected));
    }

    @Test void rejectsWrongBusinessAndProviderAndMoneyRatherThanMatchingDailyTotals() {
        var fact = fact(); fact.put("biz_no", ChannelCsvTest.REFUND);
        assertEquals("BUSINESS_MISMATCH", ReconciliationService.compare(export, row, fact, expected()));
        fact = fact(); fact.put("provider_biz_no", "other-provider");
        assertEquals("PROVIDER_MISMATCH", ReconciliationService.compare(export, row, fact, expected()));
        fact = fact(); fact.put("amount_fen", 9999L);
        assertEquals("AMOUNT_MISMATCH", ReconciliationService.compare(export, row, fact, expected()));
        fact = fact(); fact.put("currency", "USD");
        assertEquals("CURRENCY_MISMATCH", ReconciliationService.compare(export, row, fact, expected()));
    }

    @Test void rejectsCashFromAnotherBusinessDateAndRefundFromAnotherPayment() {
        var fact = fact(); fact.put("occurred_at", Instant.parse("2026-10-19T07:00:00Z"));
        assertEquals("OCCURRED_DATE_MISMATCH", ReconciliationService.compare(export, row, fact, expected()));
        var expected = expected(); expected.put("pay_no", ChannelCsvTest.REFUND);
        assertEquals("BUSINESS_MISMATCH", ReconciliationService.compare(export, row, fact(), expected));
    }
}
