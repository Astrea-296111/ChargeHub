package com.chargehub.reconcile;

import com.chargehub.infrastructure.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** Reconciliation records evidence; only the payment application's verified fact entry point moves funds. */
@Service
public class ReconciliationService {
    private final Db db;

    public ReconciliationService(Db db) { this.db = db; }

    @Transactional
    public Map<String, Object> importCsv(long operatorId, String channel, String businessDate, String billType,
                                         String timezone, int version, String csv, String reason) {
        validateOperator(operatorId, reason);
        ChannelCsv.Export export = ChannelCsv.parse(channel, businessDate, billType, timezone, version, csv);
        String hash = Json.hash(csv);
        Map<String, Object> existing = db.one("SELECT * FROM reconcile_batch WHERE channel=? AND business_date=? AND bill_type=? AND version_no=?",
                channel, businessDate, billType, version);
        if (existing != null) return repeated(existing, hash, timezone);
        // Obtain an exclusive unique-key lock directly. Catching a duplicate INSERT then upgrading its
        // shared duplicate-key lock to FOR UPDATE can deadlock when several imports arrive together.
        long batchId = db.insert("INSERT INTO reconcile_batch(channel,business_date,bill_type,source_timezone,version_no,file_hash,status,total_count) VALUES (?,?,?,?,?,?,'IMPORTING',?) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)",
                channel, businessDate, billType, timezone, version, hash, export.rows().size());
        var claimed = db.one("SELECT * FROM reconcile_batch WHERE id=? FOR UPDATE", batchId);
        if (!"IMPORTING".equals(Db.str(claimed, "status"))) return repeated(claimed, hash, timezone);

        String cashType = "PAYMENT".equals(billType) ? "CAPTURE" : "REFUND";
        Instant from = export.businessDate().atStartOfDay(export.timezone()).toInstant();
        Instant until = export.businessDate().plusDays(1).atStartOfDay(export.timezone()).toInstant();
        List<Map<String, Object>> localFacts = db.rows("SELECT * FROM cash_entry WHERE channel=? AND biz_type=? AND occurred_at>=? AND occurred_at<? ORDER BY id",
                channel, cashType, from, until);
        var byProvider = new HashMap<String, Map<String, Object>>();
        var byBusiness = new HashMap<String, Map<String, Object>>();
        for (var fact : localFacts) {
            byProvider.put(Db.str(fact, "provider_biz_no"), fact);
            byBusiness.put(Db.str(fact, "biz_no"), fact);
        }
        var matchedLocalIds = new HashSet<Long>();
        int differences = 0;
        for (var row : export.rows()) {
            var fact = byProvider.get(row.providerBizNo());
            if (fact == null) fact = byBusiness.get(row.bizNo());
            if (fact == null) fact = db.one("SELECT * FROM cash_entry WHERE channel=? AND biz_type=? AND (provider_biz_no=? OR biz_no=?) ORDER BY id LIMIT 1",
                    channel, cashType, row.providerBizNo(), row.bizNo());
            if (fact != null) matchedLocalIds.add(Db.num(fact, "id"));
            var expected = expected(billType, row.bizNo());
            String diff = compare(export, row, fact, expected);
            if (diff != null) {
                String orderNo = fact != null ? Db.str(fact, "order_no") : Db.str(expected, "order_no");
                insertItem(batchId, row.providerBizNo(), row.bizNo(), orderNo,
                        fact == null ? null : Db.num(fact, "amount_fen"), row.amountFen(), row.currency(), diff, "OPEN");
                differences++;
            }
        }
        boolean incomplete = false;
        for (var fact : localFacts) {
            if (matchedLocalIds.contains(Db.num(fact, "id"))) continue;
            // A missing export row is evidence of an incomplete comparison, never proof that money did not move.
            insertItem(batchId, Db.str(fact, "provider_biz_no"), Db.str(fact, "biz_no"), Db.str(fact, "order_no"),
                    Db.num(fact, "amount_fen"), null, Db.str(fact, "currency"), "LOCAL_ONLY", "PENDING_CONFIRMATION");
            incomplete = true;
            differences++;
        }
        String status = incomplete ? "INCOMPLETE" : differences == 0 ? "MATCHED" : "DIFF";
        db.update("UPDATE reconcile_batch SET status=?,diff_count=? WHERE id=?", status, differences, batchId);
        audit(operatorId, "IMPORT_RECONCILIATION", batchId, reason, Map.of(),
                Map.of("status", status, "fileHash", hash, "totalCount", export.rows().size(), "diffCount", differences));
        return response(db.one("SELECT * FROM reconcile_batch WHERE id=?", batchId));
    }

    /** Order orchestration can flag these orders DIFF without this module writing another aggregate. */
    public List<String> diffOrderNos(long batchId) {
        return db.rows("SELECT DISTINCT order_no FROM reconcile_item WHERE batch_id=? AND status<>'RESOLVED' AND order_no IS NOT NULL ORDER BY order_no", batchId)
                .stream().map(row -> Db.str(row, "order_no")).toList();
    }

    public Map<String, Object> items(String cursor, int limit, String status) {
        if (limit < 1 || limit > 100) throw invalid("分页大小必须在 1 到 100 之间");
        long after = 0;
        if (cursor != null && !cursor.isBlank()) {
            try {
                if (!cursor.matches("[0-9]{1,19}")) throw new NumberFormatException();
                after = Long.parseLong(cursor);
            } catch (NumberFormatException e) { throw invalid("分页游标不正确"); }
        }
        if (status != null && !Set.of("OPEN", "PENDING_CONFIRMATION", "RESOLVED").contains(status)) throw invalid("差异状态不正确");
        List<Map<String, Object>> rows = status == null
                ? db.rows("SELECT id AS item_id,batch_id,order_no,provider_biz_no,diff_type,status,local_amount_fen,channel_amount_fen FROM reconcile_item WHERE id>? ORDER BY id LIMIT ?", after, limit + 1)
                : db.rows("SELECT id AS item_id,batch_id,order_no,provider_biz_no,diff_type,status,local_amount_fen,channel_amount_fen FROM reconcile_item WHERE id>? AND status=? ORDER BY id LIMIT ?", after, status, limit + 1);
        boolean more = rows.size() > limit;
        List<Map<String, Object>> page = more ? rows.subList(0, limit) : rows;
        var result = new LinkedHashMap<String, Object>();
        result.put("items", page);
        result.put("nextCursor", more ? Long.toString(Db.num(page.getLast(), "item_id")) : null);
        return result;
    }

    /** After the orchestrator queries the independent channel and records a verified fact, close only exact matches. */
    @Transactional
    public int refreshRecovered(long operatorId, long batchId, String reason) {
        validateOperator(operatorId, reason);
        var batch = db.one("SELECT * FROM reconcile_batch WHERE id=? FOR UPDATE", batchId);
        if (batch == null) throw new BusinessException(404, "NOT_FOUND", "对账批次不存在");
        String type = "PAYMENT".equals(Db.str(batch, "bill_type")) ? "CAPTURE" : "REFUND";
        int resolved = 0;
        for (var item : db.rows("SELECT * FROM reconcile_item WHERE batch_id=? AND diff_type='CHANNEL_ONLY' AND status='OPEN' ORDER BY id FOR UPDATE", batchId)) {
            var fact = db.one("SELECT * FROM cash_entry WHERE channel=? AND biz_type=? AND provider_biz_no=? AND biz_no=?",
                    Db.str(batch, "channel"), type, Db.str(item, "provider_biz_no"), Db.str(item, "biz_no"));
            if (fact == null || Db.num(fact, "amount_fen") != Db.num(item, "channel_amount_fen")
                    || !Objects.equals(Db.str(fact, "currency"), Db.str(item, "currency"))) continue;
            Instant occurred = Db.instant(fact, "occurred_at");
            LocalDate businessDate = LocalDate.parse(Db.str(batch, "business_date"));
            if (!occurred.atZone(ZoneId.of(Db.str(batch, "source_timezone"))).toLocalDate().equals(businessDate)) continue;
            db.update("UPDATE reconcile_item SET local_amount_fen=?,status='RESOLVED',resolution=?,resolved_by=?,resolved_at=CURRENT_TIMESTAMP(3) WHERE id=? AND status='OPEN'",
                    Db.num(fact, "amount_fen"), "渠道查单后经统一资金事实入口补录，双方金额币种业务号已一致", operatorId, Db.num(item, "id"));
            resolved++;
        }
        if (resolved > 0) {
            long pending = Db.num(db.one("SELECT COUNT(*) AS count_value FROM reconcile_item WHERE batch_id=? AND status<>'RESOLVED'", batchId), "count_value");
            if (pending == 0) db.update("UPDATE reconcile_batch SET status='MATCHED' WHERE id=?", batchId);
            audit(operatorId, "VERIFY_RECOVERED_RECONCILIATION", batchId, reason,
                    Map.of("status", Db.str(batch, "status")), Map.of("resolvedCount", resolved, "remainingCount", pending));
        }
        return resolved;
    }

    private Map<String, Object> expected(String billType, String bizNo) {
        return "PAYMENT".equals(billType)
                ? db.one("SELECT order_no,pay_no,amount_fen,currency,channel FROM payment_order WHERE pay_no=?", bizNo)
                : db.one("SELECT r.order_no,r.pay_no,r.amount_fen,p.currency,p.channel FROM refund_order r JOIN payment_order p ON p.pay_no=r.pay_no WHERE r.refund_no=?", bizNo);
    }

    static String compare(ChannelCsv.Export export, ChannelCsv.Row row, Map<String, Object> fact, Map<String, Object> expected) {
        if (expected == null || !Objects.equals(row.payNo(), Db.str(expected, "pay_no"))
                || !Objects.equals(export.channel(), Db.str(expected, "channel"))) return "BUSINESS_MISMATCH";
        if (!Objects.equals(row.currency(), Db.str(expected, "currency"))) return "CURRENCY_MISMATCH";
        if (row.amountFen() != Db.num(expected, "amount_fen")) return "AMOUNT_MISMATCH";
        if (fact == null) return "CHANNEL_ONLY";
        if (!Objects.equals(row.bizNo(), Db.str(fact, "biz_no"))) return "BUSINESS_MISMATCH";
        if (!Objects.equals(row.providerBizNo(), Db.str(fact, "provider_biz_no"))) return "PROVIDER_MISMATCH";
        if (!Objects.equals(row.currency(), Db.str(fact, "currency"))) return "CURRENCY_MISMATCH";
        if (row.amountFen() != Db.num(fact, "amount_fen")) return "AMOUNT_MISMATCH";
        Instant occurred = Db.instant(fact, "occurred_at");
        if (occurred == null || !occurred.atZone(export.timezone()).toLocalDate().equals(export.businessDate())) return "OCCURRED_DATE_MISMATCH";
        return null;
    }

    private Map<String, Object> repeated(Map<String, Object> batch, String hash, String timezone) {
        if (batch == null) throw new BusinessException(409, "RECONCILIATION_CONFLICT", "同批次正在导入，请重试");
        if (!hash.equals(Db.str(batch, "file_hash")) || !timezone.equals(Db.str(batch, "source_timezone")))
            throw new BusinessException(409, "RECONCILIATION_CONFLICT", "同渠道、日期、账单类型和版本已有不同文件，请核实后使用新版本");
        return response(batch);
    }

    private Map<String, Object> response(Map<String, Object> batch) {
        return Map.of("batchId", Long.toString(Db.num(batch, "id")), "status", Db.str(batch, "status"),
                "businessDate", Db.str(batch, "business_date"), "billType", Db.str(batch, "bill_type"), "fileHash", Db.str(batch, "file_hash"));
    }

    private void insertItem(long batch, String provider, String biz, String order, Long localAmount,
                            Long channelAmount, String currency, String diff, String status) {
        db.insert("INSERT INTO reconcile_item(batch_id,provider_biz_no,biz_no,order_no,local_amount_fen,channel_amount_fen,currency,diff_type,status) VALUES (?,?,?,?,?,?,?,?,?)",
                batch, provider, biz, order, localAmount, channelAmount, currency, diff, status);
    }

    private void audit(long operator, String action, long batch, String reason, Object before, Object after) {
        db.insert("INSERT INTO ops_audit(operator_id,action,target_type,target_no,reason,before_json,after_json,request_id) VALUES (?,?,'RECONCILIATION',?,?,?,?,?)",
                operator, action, Long.toString(batch), reason.trim(), Json.write(before), Json.write(after), ApiResponse.requestIdValue());
    }

    private static void validateOperator(long operatorId, String reason) {
        if (operatorId <= 0) throw new BusinessException(401, "AUTH_REQUIRED", "需要有效的操作人员身份");
        if (reason == null || reason.isBlank() || reason.length() > 255) throw invalid("操作原因不能为空且不能超过 255 字");
    }

    private static BusinessException invalid(String message) { return new BusinessException(400, "INVALID_RECONCILIATION", message); }
}
