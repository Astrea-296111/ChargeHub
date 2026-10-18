package com.chargehub.reconcile;

import com.chargehub.infrastructure.BusinessException;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Validates a daily channel export before a transaction changes any reconciliation data. */
public final class ChannelCsv {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final String HEADER = "channel,bill_type,business_date,source_timezone,provider_biz_no,out_trade_no,out_refund_no,amount_fen,currency,occurred_at,status";
    private static final List<String> FIELDS = List.of(HEADER.split(","));
    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private ChannelCsv() {}

    public record Row(String providerBizNo, String payNo, String refundNo, long amountFen,
                      String currency, Instant occurredAt) {
        public String bizNo() { return refundNo == null ? payNo : refundNo; }
    }

    public record Export(String channel, String billType, LocalDate businessDate, ZoneId timezone,
                         List<Row> rows) {}

    public static Export parse(String channel, String businessDate, String billType, String timezone,
                               int version, String csv) {
        if (!"MOCK".equals(channel)) throw invalid("首版仅支持 MOCK 渠道");
        if (!Set.of("PAYMENT", "REFUND").contains(Objects.toString(billType, ""))) throw invalid("账单类型必须为 PAYMENT 或 REFUND");
        if (!Set.of("Asia/Shanghai", "UTC").contains(Objects.toString(timezone, ""))) throw invalid("不支持的渠道业务时区");
        if (version < 1) throw invalid("版本号必须大于零");
        LocalDate date;
        try {
            date = LocalDate.parse(Objects.toString(businessDate, ""));
            if (!date.toString().equals(businessDate)) throw invalid("业务日期必须为 YYYY-MM-DD");
        } catch (DateTimeParseException e) { throw invalid("业务日期必须为有效的 YYYY-MM-DD"); }
        if (csv == null || csv.isEmpty()) throw invalid("对账文件不能为空");
        if (csv.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new BusinessException(413, "FILE_TOO_LARGE", "对账文件不能超过 10MiB，请按业务日期分别导出");
        List<List<String>> records = records(csv);
        if (records.isEmpty() || !FIELDS.equals(records.getFirst())) throw invalid("CSV 表头必须与渠道契约完全一致");
        var rows = new ArrayList<Row>();
        var providers = new HashSet<String>();
        var businesses = new HashSet<String>();
        var zone = ZoneId.of(timezone);
        for (int i = 1; i < records.size(); i++) {
            List<String> cells = records.get(i);
            String prefix = "CSV 第 " + (i + 1) + " 行：";
            if (cells.size() != FIELDS.size()) throw invalid(prefix + "字段数量不正确");
            if (!channel.equals(cells.get(0)) || !billType.equals(cells.get(1))
                    || !businessDate.equals(cells.get(2)) || !timezone.equals(cells.get(3)))
                throw invalid(prefix + "渠道、账单类型、日期或时区与批次不一致");
            String provider = cells.get(4);
            if (!provider.matches("[A-Za-z0-9._:-]{1,64}")) throw invalid(prefix + "渠道业务号格式不正确");
            String payNo = uuid(cells.get(5), prefix);
            String refundNo = "REFUND".equals(billType) ? uuid(cells.get(6), prefix) : null;
            if ("PAYMENT".equals(billType) && !cells.get(6).isEmpty()) throw invalid(prefix + "支付账单不能包含退款号");
            if (!providers.add(provider) || !businesses.add(refundNo == null ? payNo : refundNo))
                throw invalid(prefix + "渠道业务号或平台业务号重复");
            long amount;
            try {
                if (!cells.get(7).matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
                amount = Long.parseLong(cells.get(7));
            } catch (NumberFormatException e) { throw invalid(prefix + "金额必须为可表示的正整数分"); }
            String currency = cells.get(8);
            try {
                if (!currency.matches("[A-Z]{3}")) throw new IllegalArgumentException();
                Currency.getInstance(currency);
            } catch (IllegalArgumentException e) { throw invalid(prefix + "币种必须为有效的大写 ISO 4217 代码"); }
            Instant occurred;
            try {
                if (!cells.get(9).endsWith("Z")) throw new DateTimeParseException("UTC required", cells.get(9), 0);
                occurred = Instant.parse(cells.get(9));
            } catch (DateTimeParseException e) { throw invalid(prefix + "发生时间必须是 UTC ISO-8601 时间"); }
            if (!occurred.atZone(zone).toLocalDate().equals(date)) throw invalid(prefix + "发生时间不属于该业务时区的业务日期");
            if (!"SUCCESS".equals(cells.get(10))) throw invalid(prefix + "对账资金行必须为 SUCCESS，未完成账单不能作为成功资金事实");
            rows.add(new Row(provider, payNo, refundNo, amount, currency, occurred));
        }
        return new Export(channel, billType, date, zone, List.copyOf(rows));
    }

    private static String uuid(String value, String prefix) {
        if (!value.matches(UUID_PATTERN)) throw invalid(prefix + "平台支付号和退款号必须为完整 UUID");
        return UUID.fromString(value).toString();
    }

    /** RFC 4180 quoting, UTF-8 BOM and LF/CRLF; embedded newlines are rejected by field validation. */
    private static List<List<String>> records(String input) {
        if (input.charAt(0) == '\ufeff') input = input.substring(1);
        var rows = new ArrayList<List<String>>();
        var row = new ArrayList<String>();
        var field = new StringBuilder();
        boolean quoted = false, closed = false, started = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < input.length() && input.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else { quoted = false; closed = true; }
                } else field.append(c);
                continue;
            }
            if (c == '"') {
                if (started || closed) throw invalid("CSV 引号格式错误");
                quoted = true; started = true;
            } else if (c == ',' || c == '\n' || c == '\r') {
                if (c == '\r') {
                    if (i + 1 >= input.length() || input.charAt(i + 1) != '\n') throw invalid("CSV 换行必须为 LF 或 CRLF");
                    i++;
                }
                row.add(field.toString()); field.setLength(0); started = false; closed = false;
                if (c != ',') { rows.add(List.copyOf(row)); row.clear(); }
            } else {
                if (closed) throw invalid("CSV 闭合引号后只能出现分隔符");
                field.append(c); started = true;
            }
        }
        if (quoted) throw invalid("CSV 引号未闭合");
        if (started || closed || !row.isEmpty()) { row.add(field.toString()); rows.add(List.copyOf(row)); }
        return rows;
    }

    private static BusinessException invalid(String message) { return new BusinessException(400, "INVALID_RECONCILIATION", message); }
}
