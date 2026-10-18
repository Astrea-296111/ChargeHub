package com.chargehub.station;

import com.chargehub.infrastructure.BusinessException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Canonical daily half-open intervals; published plans cover every minute exactly once. */
public final class TariffRules {
    private TariffRules() {}

    public static List<Map<String, Object>> canonicalSlots(Object source, boolean requireCoverage) {
        if (!(source instanceof List<?> input) || input.isEmpty() || input.size() > 48) {
            throw invalid("费率时段须为1至48段");
        }
        List<Map<String, Object>> slots = new ArrayList<>();
        for (Object value : input) {
            if (!(value instanceof Map<?, ?> slot)) throw invalid("费率时段格式不正确");
            int start = integer(slot.get("startMinute"), 0, 1439);
            int end = integer(slot.get("endMinute"), 1, 1440);
            int energy = integer(slot.get("energyRateFenPerKwh"), 0, 10000);
            int service = integer(slot.get("serviceRateFenPerKwh"), 0, 10000);
            if (start >= end) throw invalid("时段结束必须晚于开始");
            slots.add(Map.of("startMinute", start, "endMinute", end,
                    "energyRateFenPerKwh", energy, "serviceRateFenPerKwh", service));
        }
        slots.sort(Comparator.comparingInt(s -> ((Number) s.get("startMinute")).intValue()));
        int previous = 0;
        for (Map<String, Object> slot : slots) {
            int start = ((Number) slot.get("startMinute")).intValue();
            if (start < previous || (requireCoverage && start != previous)) {
                throw invalid("费率时段不能重叠，发布时须无空档覆盖全天");
            }
            previous = ((Number) slot.get("endMinute")).intValue();
        }
        if (requireCoverage && previous != 1440) throw invalid("发布费率须完整覆盖0至1440分钟");
        return List.copyOf(slots);
    }

    private static int integer(Object value, int min, int max) {
        try {
            int result = new BigDecimal(String.valueOf(value)).intValueExact();
            if (result < min || result > max) throw new ArithmeticException();
            return result;
        } catch (RuntimeException ex) {
            throw invalid("费率参数须为规定范围内的整数");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(400, "INVALID_TARIFF", message);
    }
}
