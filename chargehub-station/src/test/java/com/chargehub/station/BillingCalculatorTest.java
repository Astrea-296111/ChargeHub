package com.chargehub.station;

import com.chargehub.infrastructure.Json;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BillingCalculatorTest {
    @SuppressWarnings("unchecked")
    @Test
    void allEightAuthoritativeBillingVectors() throws Exception {
        Path source = Path.of("../docs/spec/07-billing-vectors.json");
        if (!Files.exists(source)) source = Path.of("docs/spec/07-billing-vectors.json");
        Map<String, Object> spec = Json.read(Files.readString(source));
        var vectors = (List<Map<String, Object>>) spec.get("vectors");
        assertEquals(8, vectors.size());
        for (var vector : vectors) {
            var segments = ((List<Map<String, Object>>) vector.get("segments")).stream().map(s ->
                    new BillingCalculator.Segment(n(s, "wh"), (int)n(s, "energyRateFenPerKwh"), (int)n(s, "serviceRateFenPerKwh"))).toList();
            var coupon = (Map<String, Object>) vector.get("coupon");
            var result = BillingCalculator.calculate(segments, Boolean.TRUE.equals(vector.get("member")),
                    coupon == null ? null : n(coupon, "thresholdFen"), coupon == null ? null : n(coupon, "discountFen"), n(vector, "prepaidFen"));
            var expected = (Map<String, Object>) vector.get("expected");
            String id = vector.get("id").toString();
            assertAll(id,
                    () -> assertEquals(n(expected, "totalWh"), result.totalWh()),
                    () -> assertEquals(n(expected, "energyFen"), result.energyFen()),
                    () -> assertEquals(n(expected, "serviceFen"), result.serviceFen()),
                    () -> assertEquals(n(expected, "memberDiscountFen"), result.memberDiscountFen()),
                    () -> assertEquals(n(expected, "couponDiscountFen"), result.couponDiscountFen()),
                    () -> assertEquals(n(expected, "payableFen"), result.payableFen()),
                    () -> assertEquals(n(expected, "refundFen"), result.refundFen()),
                    () -> assertEquals(n(expected, "shortfallFen"), result.shortfallFen()),
                    () -> assertEquals(expected.get("memberConsumed"), result.memberConsumed()),
                    () -> assertEquals(expected.get("couponUsed"), result.couponUsed()));
        }
    }

    @Test
    void rejectsInexactOrOverflowingMoneyInsteadOfWrapping() {
        assertThrows(ArithmeticException.class, () -> BillingCalculator.calculate(List.of(
                new BillingCalculator.Segment(Long.MAX_VALUE, Integer.MAX_VALUE, 0)), false, null, null, 10000));
        assertThrows(IllegalArgumentException.class, () -> BillingCalculator.calculate(List.of(), false, 5L, null, 10000));
        assertThrows(IllegalArgumentException.class, () -> new BillingCalculator.Segment(-1, 80, 50));
    }

    private static long n(Map<String, Object> value, String key) { return ((Number)value.get(key)).longValue(); }
}
