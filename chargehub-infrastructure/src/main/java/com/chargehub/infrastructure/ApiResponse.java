package com.chargehub.infrastructure;

import org.slf4j.MDC;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

public record ApiResponse(String code, String message, Object data, String requestId) {
    private static final Set<String> ACTOR_IDS = Set.of("requestedBy", "proposedBy", "decidedBy", "resolvedBy");
    public static ApiResponse ok(Object data) { return new ApiResponse("OK", "成功", sanitize(data), requestIdValue()); }
    public static ApiResponse error(String code, String message) { return new ApiResponse(code, message, null, requestIdValue()); }
    public static String requestIdValue() {
        var id = MDC.get("requestId");
        return id == null ? UUID.randomUUID().toString() : id;
    }

    /** Keep database IDs lossless for JS clients and database timestamps in UTC. */
    public static Object sanitize(Object data) {
        if (data instanceof Map<?, ?> source) {
            var target = new LinkedHashMap<String, Object>();
            source.forEach((key, value) -> {
                String name = camel(key.toString());
                target.put(name, value instanceof Number && (name.equals("id") || name.endsWith("Id") || ACTOR_IDS.contains(name))
                        ? value.toString() : sanitize(value));
            });
            return target;
        }
        if (data instanceof Collection<?> collection) return collection.stream().map(ApiResponse::sanitize).toList();
        if (data instanceof Timestamp timestamp) return timestamp.toInstant().toString();
        if (data instanceof LocalDateTime timestamp) return timestamp.toInstant(ZoneOffset.UTC).toString();
        if (data instanceof Instant instant) return instant.toString();
        return data;
    }

    private static String camel(String input) {
        if (!input.contains("_")) return input;
        StringBuilder value = new StringBuilder();
        boolean uppercase = false;
        for (char c : input.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '_') uppercase = true;
            else { value.append(uppercase ? Character.toUpperCase(c) : c); uppercase = false; }
        }
        return value.toString();
    }
}
