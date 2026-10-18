package com.chargehub.benefit;

import com.chargehub.infrastructure.BusinessException;
import com.chargehub.infrastructure.Db;
import com.chargehub.infrastructure.Json;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.chargehub.infrastructure.Db.*;

/** User-row locking serializes quota, coupon and reservation changes for each customer. */
@Service
public class BenefitService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final Db db;
    private final Clock clock;

    public BenefitService(Db db, Clock clock) { this.db = db; this.clock = clock; }

    public List<Map<String, Object>> templates() {
        Instant now = clock.instant();
        return db.rows("SELECT * FROM coupon_template WHERE status='PUBLISHED' AND claim_from<=? AND claim_until>? AND use_valid_until>? AND issued_count<total_count ORDER BY id LIMIT 100", now, now, now)
                .stream().map(this::templateView).toList();
    }

    public List<Map<String, Object>> coupons(long userId) {
        return db.rows("SELECT * FROM user_coupon WHERE user_id=? ORDER BY id DESC LIMIT 100", userId)
                .stream().map(this::couponView).toList();
    }

    /** Template cursors traverse monotonic IDs ascending and never use an OFFSET. */
    public Map<String, Object> templates(Long cursor, int limit) {
        validatePage(cursor, limit);
        Instant now = clock.instant();
        var rows = db.rows("SELECT * FROM coupon_template WHERE status='PUBLISHED' AND claim_from<=? AND claim_until>? AND use_valid_until>? AND issued_count<total_count AND id>? ORDER BY id LIMIT ?",
                now, now, now, cursor == null ? 0L : cursor, limit + 1);
        return page(rows, limit, this::templateView);
    }

    /** User-coupon cursors traverse monotonic IDs descending, preserving newest-first ordering. */
    public Map<String, Object> coupons(long userId, Long cursor, int limit) {
        validatePage(cursor, limit);
        var rows = cursor == null
                ? db.rows("SELECT * FROM user_coupon WHERE user_id=? ORDER BY id DESC LIMIT ?", userId, limit + 1)
                : db.rows("SELECT * FROM user_coupon WHERE user_id=? AND id<? ORDER BY id DESC LIMIT ?", userId, cursor, limit + 1);
        return page(rows, limit, this::couponView);
    }

    private static Map<String, Object> page(List<Map<String, Object>> rows, int limit,
                                            Function<Map<String, Object>, Map<String, Object>> view) {
        boolean more = rows.size() > limit;
        var selected = rows.subList(0, Math.min(rows.size(), limit));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", selected.stream().map(view).toList());
        result.put("nextCursor", more ? str(selected.getLast(), "id") : null);
        return result;
    }

    private static void validatePage(Long cursor, int limit) {
        if (limit < 1 || limit > 100 || (cursor != null && cursor < 1)) {
            throw invalid("分页数量须为1至100，游标须为有效正整数");
        }
    }

    public Map<String, Object> benefits(long userId) {
        Instant now = clock.instant();
        String period = month(now);
        boolean active = activeMember(userId, now);
        Map<String, Object> quota = db.one("SELECT * FROM member_quota WHERE user_id=? AND period_month=?", userId, period);
        return Map.of("memberActive", active, "periodMonth", period,
                "totalCount", quota == null ? (active ? 2 : 0) : num(quota, "total_count"),
                "reservedCount", num(quota, "reserved_count"), "usedCount", num(quota, "used_count"),
                "discountBasisPoints", 2000, "maxDiscountFen", 300);
    }

    @Transactional
    public Map<String, Object> createTemplate(Map<String, Object> request) {
        String name = text(request.get("name"));
        if (name.isBlank() || name.length() > 120 || !"ALL_STATIONS".equals(request.get("scope"))) {
            throw invalid("券名称或适用范围无效");
        }
        Instant claimFrom = timestamp(request.get("claimFrom")), claimUntil = timestamp(request.get("claimUntil"));
        Instant validFrom = timestamp(request.get("useValidFrom")), validUntil = timestamp(request.get("useValidUntil"));
        if (!claimUntil.isAfter(claimFrom) || !validUntil.isAfter(validFrom) || !validUntil.isAfter(claimFrom)) {
            throw invalid("领券和使用有效期必须为有效时间段");
        }
        long threshold = integer(request.get("thresholdFen"), 0, 10000);
        long discount = integer(request.get("discountFen"), 1, 10000);
        long total = integer(request.get("totalCount"), 0, 1000000);
        long id = db.insert("INSERT INTO coupon_template(name,claim_from,claim_until,use_valid_from,use_valid_until,threshold_fen,discount_fen,total_count,scope_snapshot) VALUES(?,?,?,?,?,?,?,?,?)",
                name, claimFrom, claimUntil, validFrom, validUntil, threshold, discount, total,
                Json.write(Map.of("scope", "ALL_STATIONS")));
        return adminTemplate(db.one("SELECT * FROM coupon_template WHERE id=?", id));
    }

    @Transactional
    public Map<String, Object> publishTemplate(long templateId) {
        var template = db.one("SELECT * FROM coupon_template WHERE id=? FOR UPDATE", templateId);
        if (template == null) throw new BusinessException(404, "COUPON_TEMPLATE_NOT_FOUND", "优惠券模板不存在");
        if (!"PUBLISHED".equals(str(template, "status"))) {
            if (!instant(template, "claim_until").isAfter(clock.instant()) || !instant(template, "use_valid_until").isAfter(clock.instant())) {
                throw new BusinessException(409, "COUPON_TEMPLATE_EXPIRED", "活动已结束，不能发布");
            }
            db.update("UPDATE coupon_template SET status='PUBLISHED' WHERE id=? AND status='DRAFT'", templateId);
        }
        return adminTemplate(db.one("SELECT * FROM coupon_template WHERE id=?", templateId));
    }

    @Transactional
    public Map<String, Object> claim(long userId, long templateId) {
        lockUser(userId);
        if (db.one("SELECT id FROM user_coupon WHERE user_id=? AND template_id=?", userId, templateId) != null) {
            throw new BusinessException(409, "COUPON_ALREADY_CLAIMED", "每个活动限领取一张券");
        }
        var template = db.one("SELECT * FROM coupon_template WHERE id=? FOR UPDATE", templateId);
        if (template == null) throw new BusinessException(404, "COUPON_TEMPLATE_NOT_FOUND", "优惠券模板不存在");
        Instant now = clock.instant();
        if (!"PUBLISHED".equals(str(template, "status")) || now.isBefore(instant(template, "claim_from"))
                || !now.isBefore(instant(template, "claim_until")) || !now.isBefore(instant(template, "use_valid_until"))) {
            throw new BusinessException(409, "COUPON_NOT_CLAIMABLE", "优惠券不在可领取时间内");
        }
        if (db.update("UPDATE coupon_template SET issued_count=issued_count+1 WHERE id=? AND issued_count<total_count", templateId) != 1) {
            throw new BusinessException(409, "COUPON_SOLD_OUT", "优惠券已领完");
        }
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("templateId", String.valueOf(templateId));
        rule.put("thresholdFen", num(template, "threshold_fen"));
        rule.put("discountFen", num(template, "discount_fen"));
        rule.put("scope", "ALL_STATIONS");
        long id = db.insert("INSERT INTO user_coupon(template_id,user_id,valid_from,valid_until,rule_snapshot,claimed_at) VALUES(?,?,?,?,?,?)",
                templateId, userId, instant(template, "use_valid_from"), instant(template, "use_valid_until"), Json.write(rule), now);
        return couponView(db.one("SELECT * FROM user_coupon WHERE id=?", id));
    }

    /** Read-only eligibility for quotations. This neither creates quota accounts nor locks a coupon. */
    public Map<String, Object> preview(long userId, boolean useMember, Long couponId) {
        Instant now = clock.instant();
        Map<String, Object> memberRule = null, couponRule = null;
        if (useMember) {
            requireMember(userId, now);
            var quota = db.one("SELECT * FROM member_quota WHERE user_id=? AND period_month=?", userId, month(now));
            if (quota != null && num(quota, "reserved_count") + num(quota, "used_count") >= num(quota, "total_count")) {
                throw new BusinessException(409, "MEMBER_QUOTA_EXHAUSTED", "本月会员优惠次数已用完");
            }
            memberRule = memberRule(now);
        }
        if (couponId != null) {
            var coupon = db.one("SELECT * FROM user_coupon WHERE id=? AND user_id=?", couponId, userId);
            requireCoupon(coupon, now);
            couponRule = Json.read(str(coupon, "rule_snapshot"));
        }
        return eligibility(useMember, memberRule, couponRule);
    }

    /** Must share the order-creation transaction. Repeat execution returns the original reservation. */
    @Transactional
    public Map<String, Object> reserve(long userId, String orderNo, boolean useMember, Long couponId) {
        lockUser(userId);
        var existing = db.one("SELECT * FROM benefit_reservation WHERE order_no=? FOR UPDATE", orderNo);
        if (existing != null) {
            if (num(existing, "user_id") != userId || (num(existing, "quota_id") != 0) != useMember
                    || !java.util.Objects.equals(nullableLong(existing, "coupon_id"), couponId)) {
                throw new BusinessException(409, "BENEFIT_RESERVATION_CONFLICT", "订单权益预占参数冲突");
            }
            return eligibility(useMember, Json.read(str(existing, "member_rule_snapshot")),
                    Json.read(str(existing, "coupon_rule_snapshot")));
        }
        Instant now = clock.instant();
        Long quotaId = null;
        Map<String, Object> memberRule = null, couponRule = null;
        if (useMember) {
            requireMember(userId, now);
            String period = month(now);
            // User lock also serializes first creation of a new month's fixed two-use account.
            var quota = db.one("SELECT * FROM member_quota WHERE user_id=? AND period_month=? FOR UPDATE", userId, period);
            if (quota == null) {
                quotaId = db.insert("INSERT INTO member_quota(user_id,period_month,total_count) VALUES(?,?,2)", userId, period);
            } else quotaId = num(quota, "id");
            if (db.update("UPDATE member_quota SET reserved_count=reserved_count+1,version=version+1 WHERE id=? AND used_count+reserved_count<total_count", quotaId) != 1) {
                throw new BusinessException(409, "MEMBER_QUOTA_EXHAUSTED", "本月会员优惠次数已用完");
            }
            db.update("INSERT INTO quota_ledger(quota_id,order_no,action,reserved_delta,used_delta) VALUES(?,?,'RESERVE',1,0)", quotaId, orderNo);
            memberRule = memberRule(now);
        }
        if (couponId != null) {
            var coupon = db.one("SELECT * FROM user_coupon WHERE id=? AND user_id=? FOR UPDATE", couponId, userId);
            requireCoupon(coupon, now);
            if (db.update("UPDATE user_coupon SET status='LOCKED',locked_order_no=?,version=version+1 WHERE id=? AND user_id=? AND status='AVAILABLE'", orderNo, couponId, userId) != 1) {
                throw new BusinessException(409, "COUPON_UNAVAILABLE", "优惠券已被其他订单占用");
            }
            couponTransition(couponId, orderNo, "LOCK", "AVAILABLE", "LOCKED");
            couponRule = Json.read(str(coupon, "rule_snapshot"));
        }
        db.update("INSERT INTO benefit_reservation(order_no,user_id,quota_id,coupon_id,member_state,coupon_state,member_rule_snapshot,coupon_rule_snapshot) VALUES(?,?,?,?,?,?,?,?)",
                orderNo, userId, quotaId, couponId, useMember ? "RESERVED" : "NONE", couponId != null ? "LOCKED" : "NONE",
                memberRule == null ? null : Json.write(memberRule), couponRule == null ? null : Json.write(couponRule));
        return eligibility(useMember, memberRule, couponRule);
    }

    @Transactional
    public void finalizeReservation(String orderNo, long memberDiscountFen, long couponDiscountFen) {
        if (memberDiscountFen < 0 || couponDiscountFen < 0) throw invalid("优惠金额不得为负数");
        finish(orderNo, memberDiscountFen > 0, couponDiscountFen > 0);
    }

    @Transactional
    public void release(String orderNo) { finish(orderNo, false, false); }

    private void finish(String orderNo, boolean consumeMember, boolean useCoupon) {
        var lookup = db.one("SELECT user_id FROM benefit_reservation WHERE order_no=?", orderNo);
        if (lookup == null) return;
        // Disabling login must not prevent releasing or finalizing already reserved resources.
        lockUser(num(lookup, "user_id"), false);
        var reservation = db.one("SELECT * FROM benefit_reservation WHERE order_no=? FOR UPDATE", orderNo);
        Instant now = clock.instant();
        if ("RESERVED".equals(str(reservation, "member_state"))) {
            long quotaId = num(reservation, "quota_id");
            if (db.update("UPDATE member_quota SET reserved_count=reserved_count-1,used_count=used_count+?,version=version+1 WHERE id=? AND reserved_count>0", consumeMember ? 1 : 0, quotaId) != 1) {
                throw new IllegalStateException("Reserved member quota is inconsistent");
            }
            db.update("INSERT INTO quota_ledger(quota_id,order_no,action,reserved_delta,used_delta) VALUES(?,?,?,-1,?)",
                    quotaId, orderNo, consumeMember ? "CONSUME" : "RELEASE", consumeMember ? 1 : 0);
            db.update("UPDATE benefit_reservation SET member_state=? WHERE id=?", consumeMember ? "CONSUMED" : "RELEASED", num(reservation, "id"));
        }
        if ("LOCKED".equals(str(reservation, "coupon_state"))) {
            long couponId = num(reservation, "coupon_id");
            var coupon = db.one("SELECT * FROM user_coupon WHERE id=? FOR UPDATE", couponId);
            if (coupon == null || !"LOCKED".equals(str(coupon, "status")) || !orderNo.equals(str(coupon, "locked_order_no"))) {
                throw new IllegalStateException("Reserved coupon ownership is inconsistent");
            }
            // Expiry after reservation does not revoke a benefit used by a valid charging session.
            String status = useCoupon ? "USED" : !now.isBefore(instant(coupon, "valid_until")) ? "EXPIRED" : "AVAILABLE";
            db.update("UPDATE user_coupon SET status=?,locked_order_no=NULL,used_at=?,version=version+1 WHERE id=? AND status='LOCKED' AND locked_order_no=?",
                    status, useCoupon ? now : null, couponId, orderNo);
            couponTransition(couponId, orderNo, useCoupon ? "USE" : "RELEASE", "LOCKED", status);
            db.update("UPDATE benefit_reservation SET coupon_state=? WHERE id=?", useCoupon ? "USED" : "RELEASED", num(reservation, "id"));
        }
        db.update("UPDATE benefit_reservation SET finalized_at=COALESCE(finalized_at,?) WHERE id=?", now, num(reservation, "id"));
    }

    private void couponTransition(long couponId, String orderNo, String action, String from, String to) {
        db.update("INSERT INTO coupon_transition_log(coupon_id,order_no,action,from_status,to_status) VALUES(?,?,?,?,?)", couponId, orderNo, action, from, to);
    }

    private void lockUser(long userId) {
        lockUser(userId, true);
    }

    private void lockUser(long userId, boolean requireEnabled) {
        if (db.one("SELECT id FROM iam_user WHERE id=?" + (requireEnabled ? " AND enabled=1" : "") + " FOR UPDATE", userId) == null) {
            throw new BusinessException(404, "USER_NOT_FOUND", "用户不存在或已停用");
        }
    }

    private boolean activeMember(long userId, Instant now) {
        return db.one("SELECT id FROM membership WHERE user_id=? AND status='ACTIVE' AND valid_from<=? AND valid_until>?", userId, now, now) != null;
    }

    private void requireMember(long userId, Instant now) {
        if (!activeMember(userId, now)) throw new BusinessException(409, "MEMBER_INACTIVE", "当前没有有效会员资格");
    }

    private void requireCoupon(Map<String, Object> coupon, Instant now) {
        if (coupon == null) throw new BusinessException(404, "COUPON_NOT_FOUND", "优惠券不存在");
        if (!"AVAILABLE".equals(str(coupon, "status")) || now.isBefore(instant(coupon, "valid_from")) || !now.isBefore(instant(coupon, "valid_until"))) {
            throw new BusinessException(409, "COUPON_UNAVAILABLE", "优惠券不可用或不在有效期内");
        }
    }

    private static Map<String, Object> memberRule(Instant now) {
        return Map.of("periodMonth", month(now), "discountBasisPoints", 2000, "maxDiscountFen", 300);
    }

    private static Map<String, Object> eligibility(boolean member, Map<String, Object> memberRule, Map<String, Object> couponRule) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("member", member);
        result.put("couponThresholdFen", couponRule == null || couponRule.isEmpty() ? null : num(couponRule, "thresholdFen"));
        result.put("couponDiscountFen", couponRule == null || couponRule.isEmpty() ? null : num(couponRule, "discountFen"));
        result.put("memberRuleSnapshot", memberRule);
        result.put("couponRuleSnapshot", couponRule);
        return result;
    }

    private Map<String, Object> couponView(Map<String, Object> coupon) {
        var rule = Json.read(str(coupon, "rule_snapshot"));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("couponId", str(coupon, "id"));
        view.put("templateId", str(coupon, "template_id"));
        String status = str(coupon, "status");
        view.put("status", "AVAILABLE".equals(status) && !clock.instant().isBefore(instant(coupon, "valid_until")) ? "EXPIRED" : status);
        view.put("lockedOrderNo", str(coupon, "locked_order_no"));
        view.put("validUntil", instant(coupon, "valid_until"));
        view.put("thresholdFen", num(rule, "thresholdFen"));
        view.put("discountFen", num(rule, "discountFen"));
        return view;
    }

    private Map<String, Object> templateView(Map<String, Object> template) {
        return Map.of("templateId", str(template, "id"), "name", str(template, "name"),
                "thresholdFen", num(template, "threshold_fen"), "discountFen", num(template, "discount_fen"),
                "remainingCount", num(template, "total_count") - num(template, "issued_count"),
                "claimUntil", instant(template, "claim_until"), "validUntil", instant(template, "use_valid_until"), "scope", "ALL_STATIONS");
    }

    private static Map<String, Object> adminTemplate(Map<String, Object> template) {
        return Map.of("templateId", str(template, "id"), "status", str(template, "status"), "name", str(template, "name"));
    }

    private static Long nullableLong(Map<String, Object> row, String key) { return str(row, key) == null ? null : num(row, key); }
    private static String month(Instant time) { return YearMonth.from(time.atZone(BUSINESS_ZONE)).toString(); }
    private static String text(Object value) { return value == null ? "" : value.toString().trim(); }
    private static Instant timestamp(Object value) {
        try { return Instant.parse(text(value)); }
        catch (RuntimeException ex) { throw invalid("时间须为包含时区的ISO日期时间"); }
    }
    private static long integer(Object value, long min, long max) {
        try {
            long result = new BigDecimal(text(value)).longValueExact();
            if (result < min || result > max) throw new ArithmeticException();
            return result;
        } catch (RuntimeException ex) { throw invalid("金额与数量须为规定范围内的整数"); }
    }
    private static BusinessException invalid(String message) { return new BusinessException(400, "INVALID_REQUEST", message); }
}
