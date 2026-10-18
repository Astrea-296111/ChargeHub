package com.chargehub.station;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/** Exact, stateless accounting shared by quotations and immutable final bills. */
public final class BillingCalculator {
    private BillingCalculator() {}

    public record Segment(long wh, int energyRateFenPerKwh, int serviceRateFenPerKwh) {
        public Segment {
            if (wh < 0 || energyRateFenPerKwh < 0 || serviceRateFenPerKwh < 0) {
                throw new IllegalArgumentException("电量与费率不得为负数");
            }
        }
    }

    public record Result(long totalWh, long energyFen, long serviceFen,
                         long memberDiscountFen, long couponDiscountFen,
                         long payableFen, long refundFen, long shortfallFen) {
        public boolean memberConsumed() { return memberDiscountFen > 0; }
        public boolean couponUsed() { return couponDiscountFen > 0; }
        public Map<String, Object> amounts() {
            return Map.of("totalWh", totalWh, "energyFen", energyFen, "serviceFen", serviceFen,
                    "memberDiscountFen", memberDiscountFen, "couponDiscountFen", couponDiscountFen,
                    "payableFen", payableFen);
        }
    }

    public static long componentFen(long wh, int rateFenPerKwh) {
        if (wh < 0 || rateFenPerKwh < 0) throw new IllegalArgumentException("电量与费率不得为负数");
        return BigDecimal.valueOf(wh).multiply(BigDecimal.valueOf(rateFenPerKwh))
                .divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP).longValueExact();
    }

    public static Result calculate(List<Segment> segments, boolean member,
                                   Long couponThresholdFen, Long couponDiscountFen, long prepaidFen) {
        if (prepaidFen < 0 || (couponThresholdFen != null && couponThresholdFen < 0)
                || (couponDiscountFen != null && couponDiscountFen < 0)) {
            throw new IllegalArgumentException("金额不得为负数");
        }
        if ((couponThresholdFen == null) != (couponDiscountFen == null)) {
            throw new IllegalArgumentException("优惠券门槛和优惠金额必须同时提供");
        }
        long totalWh = 0, energy = 0, service = 0;
        for (Segment segment : segments) {
            totalWh = Math.addExact(totalWh, segment.wh());
            energy = Math.addExact(energy, componentFen(segment.wh(), segment.energyRateFenPerKwh()));
            service = Math.addExact(service, componentFen(segment.wh(), segment.serviceRateFenPerKwh()));
        }
        long memberDiscount = member ? Math.min(300, BigDecimal.valueOf(service)
                .multiply(new BigDecimal("0.20")).setScale(0, RoundingMode.HALF_UP).longValueExact()) : 0;
        long remainingService = service - memberDiscount;
        long couponDiscount = couponThresholdFen != null && remainingService >= couponThresholdFen
                ? Math.min(remainingService, couponDiscountFen) : 0;
        long payable = Math.addExact(energy, remainingService - couponDiscount);
        return new Result(totalWh, energy, service, memberDiscount, couponDiscount, payable,
                Math.max(0, prepaidFen - payable), Math.max(0, payable - prepaidFen));
    }
}
