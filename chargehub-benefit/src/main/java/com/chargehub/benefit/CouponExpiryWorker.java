package com.chargehub.benefit;

import com.chargehub.infrastructure.Db;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded batches, one short transaction per user; locked coupons retain their frozen eligibility. */
@Component
@Profile("worker")
public class CouponExpiryWorker {
    private static final Logger LOG = LoggerFactory.getLogger(CouponExpiryWorker.class);
    private final Db db;
    private final BenefitService benefits;
    private final Clock clock;

    public CouponExpiryWorker(Db db, BenefitService benefits, Clock clock) {
        this.db = db;
        this.benefits = benefits;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${chargehub.coupon-expiry-delay-ms:60000}")
    public void expire() {
        for (var coupon : db.rows("SELECT id,user_id FROM user_coupon WHERE status='AVAILABLE' AND valid_until<=? ORDER BY valid_until,id LIMIT 100", clock.instant())) {
            try {
                benefits.expireAvailable(Db.num(coupon, "user_id"), Db.num(coupon, "id"));
            } catch (RuntimeException deferred) {
                LOG.warn("Coupon expiry deferred couponId={} type={}", Db.num(coupon, "id"), deferred.getClass().getSimpleName());
            }
        }
    }
}
