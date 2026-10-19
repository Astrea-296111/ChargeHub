package com.chargehub.identity;

import com.chargehub.infrastructure.BusinessException;
import java.util.*;

/** Explicit allowlists keep credentials, role names and audit reasons out of coercion paths. */
final class IdentityValidation {
    static final Set<String> ROLES = Set.of("CUSTOMER", "OPS", "FINANCE", "ADMIN");

    private IdentityValidation() {}

    static void only(Map<String, Object> body, String... names) {
        if (body == null || !Set.of(names).containsAll(body.keySet())) throw invalid();
    }

    static String text(Map<String, Object> body, String name, int min, int max) {
        Object value = body.get(name);
        if (!(value instanceof String text) || text.length() < min || text.length() > max
                || text.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return text;
    }

    static String reason(Map<String, Object> body) {
        String value = text(body, "reason", 5, 255).strip();
        if (value.length() < 5) throw invalid();
        return value;
    }

    static void validateReason(String reason) {
        reason(Map.of("reason", reason == null ? "" : reason));
    }

    static Set<String> roles(Map<String, Object> body) {
        Object value = body.get("roles");
        if (!(value instanceof List<?> values) || values.isEmpty() || values.size() > ROLES.size()) throw invalid();
        var roles = new TreeSet<String>();
        for (Object role : values) {
            if (!(role instanceof String code) || !ROLES.contains(code) || !roles.add(code)) throw invalid();
        }
        return Collections.unmodifiableSet(roles);
    }

    static long positiveId(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
            return Long.parseLong(value);
        } catch (NumberFormatException exception) { throw invalid(); }
    }

    static BusinessException invalid() {
        return new BusinessException(400, "INVALID_REQUEST", "请求字段或格式不正确");
    }
}
