package com.chargehub.reconcile;

import com.chargehub.infrastructure.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ChannelCsvTest {
    static final String PAY = "00000000-0000-4000-8000-000000000001";
    static final String REFUND = "00000000-0000-4000-8000-000000000002";
    static final String LINE = "MOCK,PAYMENT,2026-10-18,Asia/Shanghai,mock-pay-0001," + PAY + ",,10000,CNY,2026-10-18T07:00:00Z,SUCCESS";
    static final String CSV = ChannelCsv.HEADER + "\n" + LINE + "\n";

    static ChannelCsv.Export parse(String csv) { return ChannelCsv.parse("MOCK", "2026-10-18", "PAYMENT", "Asia/Shanghai", 1, csv); }

    @Test void acceptsCanonicalExportAndQuotedCrLfBom() {
        assertEquals(10000, parse(CSV).rows().getFirst().amountFen());
        var parsed = parse("\ufeff" + ChannelCsv.HEADER + "\r\n" + LINE.replace("mock-pay-0001", "\"mock-pay-0001\"") + "\r\n");
        assertEquals(PAY, parsed.rows().getFirst().payNo());
    }

    @Test void archivesUtcInstantInDeclaredBusinessTimezone() {
        assertEquals(1, parse(CSV.replace("2026-10-18T07:00:00Z", "2026-10-17T16:00:00Z")).rows().size());
        assertThrows(BusinessException.class, () -> parse(CSV.replace("2026-10-18T07:00:00Z", "2026-10-17T15:59:59Z")));
        assertThrows(BusinessException.class, () -> parse(CSV.replace("2026-10-18T07:00:00Z", "2026-10-18T07:00:00+00:00")));
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "1.00", "01", "100.5", "9223372036854775808", "1e4", " 10000"})
    void rejectsNonPositiveOrNonIntegerOrOverflowMoney(String amount) {
        assertThrows(BusinessException.class, () -> parse(CSV.replace(",10000,", "," + amount + ",")));
    }

    @Test void enforcesMatchingBatchMetadataAndHeader() {
        assertThrows(BusinessException.class, () -> parse(CSV.replace("channel,bill_type", "bill_type,channel")));
        assertThrows(BusinessException.class, () -> parse(CSV.replace("MOCK,PAYMENT", "OTHER,PAYMENT")));
        assertThrows(BusinessException.class, () -> parse(CSV.replace("Asia/Shanghai,mock-pay", "UTC,mock-pay")));
        assertThrows(BusinessException.class, () -> parse(CSV.replace("MOCK,PAYMENT,2026-10-18", "MOCK,PAYMENT,2026-10-19")));
        assertThrows(BusinessException.class, () -> ChannelCsv.parse("MOCK", "2026-02-30", "PAYMENT", "UTC", 1, CSV));
        assertThrows(BusinessException.class, () -> ChannelCsv.parse("MOCK", "2026-10-18", "PAYMENT", "UTC", 0, CSV));
    }

    @Test void rejectsDuplicateProviderOrBusinessIdentities() {
        assertThrows(BusinessException.class, () -> parse(CSV + LINE + "\n"));
        assertThrows(BusinessException.class, () -> parse(CSV + LINE.replace("mock-pay-0001", "mock-pay-0002") + "\n"));
    }

    @Test void validatesRefundOriginalPaymentAndRefundNumbers() {
        String line = LINE.replace("MOCK,PAYMENT", "MOCK,REFUND").replace(PAY + ",,", PAY + "," + REFUND + ",");
        var refund = ChannelCsv.parse("MOCK", "2026-10-18", "REFUND", "Asia/Shanghai", 1, ChannelCsv.HEADER + "\n" + line);
        assertEquals(REFUND, refund.rows().getFirst().bizNo());
        assertThrows(BusinessException.class, () -> ChannelCsv.parse("MOCK", "2026-10-18", "REFUND", "Asia/Shanghai", 1, CSV.replace("MOCK,PAYMENT", "MOCK,REFUND")));
        assertThrows(BusinessException.class, () -> parse(CSV.replace(PAY, "1-1-1-1-1")));
        assertThrows(BusinessException.class, () -> parse(CSV.replace(PAY + ",,", PAY + "," + REFUND + ",")));
    }

    @Test void rejectsMalformedAndIncompleteRows() {
        assertThrows(BusinessException.class, () -> parse(CSV.replace(",SUCCESS", ",PENDING")));
        assertThrows(BusinessException.class, () -> parse(CSV.replace(",CNY,", ",cny,")));
        assertThrows(BusinessException.class, () -> parse(CSV.replace("mock-pay-0001", "\"mock-pay-0001")));
        assertThrows(BusinessException.class, () -> parse(CSV.replace("mock-pay-0001", "\"mock-pay-0001\"x")));
        assertThrows(BusinessException.class, () -> parse(CSV + "\n"));
        assertThrows(BusinessException.class, () -> parse(CSV.replace("\n", "\r")));
    }

    @Test void rejectsOversizedFilesBeforeParsing() {
        var error = assertThrows(BusinessException.class, () -> parse("x".repeat(ChannelCsv.MAX_BYTES + 1)));
        assertEquals(413, error.status());
    }

    @Test void allowsEmptyDailyExportButKeepsKnownCurrencyForDifferenceDetection() {
        assertTrue(parse(ChannelCsv.HEADER + "\n").rows().isEmpty());
        assertEquals("USD", parse(CSV.replace(",CNY,", ",USD,")).rows().getFirst().currency());
    }
}
