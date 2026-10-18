package com.chargehub.reconcile;

import com.chargehub.infrastructure.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReconciliationServiceTest {
    @Test void localOnlyFundsBecomeIncompleteEvidenceAndNeverGetDeletedOrDeclaredAbsent() {
        Db db = mock(Db.class);
        var local = Map.<String, Object>of("id", 9L, "provider_biz_no", "local-provider", "biz_no", ChannelCsvTest.PAY,
                "order_no", ChannelCsvTest.REFUND, "amount_fen", 10000L, "currency", "CNY",
                "occurred_at", Instant.parse("2026-10-18T07:00:00Z"));
        var batch = new HashMap<String, Object>(Map.of("id", 1L, "status", "IMPORTING", "business_date", "2026-10-18",
                "bill_type", "PAYMENT", "file_hash", Json.hash(ChannelCsv.HEADER + "\n")));
        var insertedItem = new AtomicReference<Object[]>();
        when(db.one(anyString(), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            return sql.contains("WHERE id=?") ? batch : null;
        });
        when(db.rows(anyString(), any(Object[].class))).thenAnswer(call ->
                ((String) call.getArgument(0)).contains("FROM cash_entry") ? List.of(local) : List.of());
        when(db.insert(anyString(), any(Object[].class))).thenAnswer(call -> {
            if (((String) call.getArgument(0)).startsWith("INSERT INTO reconcile_item")) insertedItem.set((Object[]) call.getRawArguments()[1]);
            return 1L;
        });
        when(db.update(anyString(), any(Object[].class))).thenAnswer(call -> {
            Object[] args = (Object[]) call.getRawArguments()[1];
            batch.put("status", args[0]);
            return 1;
        });
        var response = new ReconciliationService(db).importCsv(7, "MOCK", "2026-10-18", "PAYMENT", "Asia/Shanghai", 1,
                ChannelCsv.HEADER + "\n", "核对完整性待确认的渠道文件");
        assertEquals("INCOMPLETE", response.get("status"));
        assertNotNull(insertedItem.get());
        assertEquals("LOCAL_ONLY", insertedItem.get()[7]);
        assertEquals("PENDING_CONFIRMATION", insertedItem.get()[8]);
        assertNull(insertedItem.get()[5]);
        verify(db, never()).update(matches("(?is).*(UPDATE|DELETE FROM) (cash_entry|payment_order|refund_order).*"), any(Object[].class));
    }

    @Test void sameBatchWithSameHashReturnsExistingResultWithoutReprocessing() {
        Db db = mock(Db.class);
        var batch = Map.<String, Object>of("id", 12L, "status", "MATCHED", "business_date", "2026-10-18", "bill_type", "PAYMENT",
                "file_hash", Json.hash(ChannelCsvTest.CSV), "source_timezone", "Asia/Shanghai");
        when(db.one(anyString(), any(Object[].class))).thenReturn(batch);
        var result = new ReconciliationService(db).importCsv(7, "MOCK", "2026-10-18", "PAYMENT", "Asia/Shanghai", 1,
                ChannelCsvTest.CSV, "重复导入校验");
        assertEquals("12", result.get("batchId"));
        assertEquals("MATCHED", result.get("status"));
        verify(db, never()).insert(anyString(), any(Object[].class));
        verify(db, never()).rows(anyString(), any(Object[].class));
    }

    @Test void changedContentsCannotHideBehindExistingBatchMetadata() {
        Db db = mock(Db.class);
        when(db.one(anyString(), any(Object[].class))).thenReturn(Map.of("file_hash", Json.hash(ChannelCsvTest.CSV), "source_timezone", "Asia/Shanghai"));
        var error = assertThrows(BusinessException.class, () -> new ReconciliationService(db).importCsv(7, "MOCK", "2026-10-18", "PAYMENT", "Asia/Shanghai", 1,
                ChannelCsvTest.CSV.replace(",10000,", ",9999,"), "错误金额再次导入"));
        assertEquals(409, error.status());
        verify(db, never()).insert(anyString(), any(Object[].class));
    }
}
