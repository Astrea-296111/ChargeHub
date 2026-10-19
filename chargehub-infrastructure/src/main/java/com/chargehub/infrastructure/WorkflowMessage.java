package com.chargehub.infrastructure;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** A versioned transport envelope, always checked against its original database Outbox before dispatch. */
public record WorkflowMessage(String eventId, String eventName, int schemaVersion, String aggregateType,
                              String aggregateId, long aggregateVersion, String traceId, String occurredAt,
                              Map<String, Object> payload) {
    public static final Set<String> EVENT_NAMES = Set.of("PaymentCreateRequested", "PaymentCloseRequested",
            "ChargeStartRequested", "ChargeStopRequested", "DeviceQueryRequested", "RefundRequested",
            "SessionStopped", "PaymentCaptured", "BillFinalized", "RefundSucceeded", "DeviceMeterReplayRequested");
    public static final int MAX_BYTES = 256 * 1024;
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    public WorkflowMessage {
        if (!uuid(eventId) || !EVENT_NAMES.contains(Objects.toString(eventName, "")) || schemaVersion != 1 || !"ORDER".equals(aggregateType)
                || !uuid(aggregateId) || aggregateVersion < 1 || traceId == null || !traceId.matches("[A-Za-z0-9._:-]{1,64}")
                || payload == null) throw invalid("INVALID_MESSAGE_METADATA");
        try { Instant.parse(occurredAt); } catch (RuntimeException error) { throw invalid("INVALID_MESSAGE_TIME"); }
        if (!uuid(Objects.toString(payload.get("orderNo"), ""))) throw invalid("INVALID_MESSAGE_BUSINESS_ID");
        for (String key : requiredIds(eventName)) if (!uuid(Objects.toString(payload.get(key), ""))) throw invalid("INVALID_MESSAGE_BUSINESS_ID");
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    public static WorkflowMessage fromOutbox(Map<String, Object> event) {
        return new WorkflowMessage(Db.str(event, "event_id"), Db.str(event, "event_name"), (int) Db.num(event, "schema_version"),
                Db.str(event, "aggregate_type"), Db.str(event, "aggregate_id"), Db.num(event, "aggregate_version"),
                Db.str(event, "trace_id"), Db.instant(event, "created_at").toString(), Json.read(Db.str(event, "payload")));
    }

    @SuppressWarnings("unchecked")
    public static WorkflowMessage decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid("INVALID_MESSAGE_SIZE");
        Map<String, Object> map;
        try {
            String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            map = Json.read(body);
        } catch (Exception error) { throw invalid("INVALID_MESSAGE_ENCODING"); }
        if (!map.keySet().equals(Set.of("eventId", "eventName", "schemaVersion", "aggregateType", "aggregateId",
                "aggregateVersion", "traceId", "occurredAt", "payload")) || !(map.get("payload") instanceof Map<?, ?>))
            throw invalid("INVALID_MESSAGE_FIELDS");
        return new WorkflowMessage(string(map, "eventId"), string(map, "eventName"), Math.toIntExact(integer(map.get("schemaVersion"))),
                string(map, "aggregateType"), string(map, "aggregateId"), integer(map.get("aggregateVersion")),
                string(map, "traceId"), string(map, "occurredAt"), (Map<String, Object>) map.get("payload"));
    }

    public byte[] encode() {
        byte[] bytes = Json.write(canonical(asMap())).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw invalid("INVALID_MESSAGE_SIZE");
        return bytes;
    }

    public String hash() { return Json.hash(encode()); }

    private Map<String, Object> asMap() {
        return Map.of("eventId", eventId, "eventName", eventName, "schemaVersion", schemaVersion,
                "aggregateType", aggregateType, "aggregateId", aggregateId, "aggregateVersion", aggregateVersion,
                "traceId", traceId, "occurredAt", occurredAt, "payload", payload);
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, item) -> sorted.put(key.toString(), canonical(item)));
            return sorted;
        }
        if (value instanceof Collection<?> list) return list.stream().map(WorkflowMessage::canonical).toList();
        return value;
    }

    private static List<String> requiredIds(String name) {
        return switch (name) {
            case "PaymentCreateRequested", "PaymentCloseRequested" -> List.of("payNo");
            case "ChargeStartRequested" -> List.of("commandId");
            case "RefundRequested", "RefundSucceeded" -> List.of("refundNo");
            case "BillFinalized" -> List.of("billNo");
            case "DeviceMeterReplayRequested" -> List.of("requestNo", "sessionNo");
            default -> List.of();
        };
    }

    private static String string(Map<String, Object> map, String key) {
        if (!(map.get(key) instanceof String value)) throw invalid("INVALID_MESSAGE_FIELDS");
        return value;
    }

    private static long integer(Object value) {
        try {
            if (!(value instanceof Number)) throw new ArithmeticException();
            return new BigDecimal(value.toString()).longValueExact();
        } catch (RuntimeException error) { throw invalid("INVALID_MESSAGE_VERSION"); }
    }

    private static boolean uuid(String value) { return value != null && value.matches(UUID_PATTERN); }
    private static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }
}
