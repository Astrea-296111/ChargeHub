package com.chargehub.benefit;

import com.chargehub.infrastructure.BusinessException;
import com.chargehub.infrastructure.Db;
import com.chargehub.infrastructure.Json;
import jakarta.validation.Validator;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.chargehub.infrastructure.Db.*;

/** Operational changes preserve frozen user coupons, old reservation months and consumed quotas. */
@Service
public class BenefitOperationsService {
    private final Db db;
    private final Validator validator;

    public BenefitOperationsService(Db db, Validator validator) { this.db = db; this.validator = validator; }

    public Map<String, Object> templates(Long cursor, int limit) {
        if (limit < 1 || limit > 100 || (cursor != null && cursor < 1)) throw invalid("分页参数无效");
        var found = db.rows("SELECT * FROM coupon_template WHERE id>? ORDER BY id LIMIT ?", cursor == null ? 0L : cursor, limit + 1);
        var rows = found.subList(0, Math.min(limit, found.size()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", rows);
        result.put("nextCursor", found.size() > limit ? str(rows.getLast(), "id") : null);
        return result;
    }

    public Map<String, Object> template(long id) {
        var result = db.one("SELECT * FROM coupon_template WHERE id=?", id);
        if (result == null) throw new BusinessException(404, "COUPON_TEMPLATE_NOT_FOUND", "优惠券模板不存在");
        return result;
    }

    @Transactional
    public Map<String, Object> updateTemplate(long operator, long id, BenefitCommands.TemplateInput input) {
        validate(input, input == null ? null : input.reason());
        if (!input.claimUntil().isAfter(input.claimFrom()) || !input.useValidUntil().isAfter(input.useValidFrom())
                || !input.useValidUntil().isAfter(input.claimFrom())) throw invalid("活动有效期不合法");
        var before = db.one("SELECT * FROM coupon_template WHERE id=? FOR UPDATE", id);
        if (before == null) throw new BusinessException(404, "COUPON_TEMPLATE_NOT_FOUND", "优惠券模板不存在");
        if (!"DRAFT".equals(str(before, "status")) || num(before, "issued_count") != 0) {
            throw new BusinessException(409, "COUPON_TEMPLATE_IMMUTABLE", "已经发布或发券的模板不能修改，请创建新模板");
        }
        db.update("UPDATE coupon_template SET name=?,claim_from=?,claim_until=?,use_valid_from=?,use_valid_until=?,threshold_fen=?,discount_fen=?,total_count=?,scope_snapshot=? WHERE id=? AND status='DRAFT'",
                input.name().trim(), input.claimFrom(), input.claimUntil(), input.useValidFrom(), input.useValidUntil(),
                input.thresholdFen(), input.discountFen(), input.totalCount(), Json.write(Map.of("scope", input.scope())), id);
        var after = template(id);
        audit(operator, "COUPON_TEMPLATE_UPDATE", "CouponTemplate", id, input.reason(), before, after);
        return after;
    }

    public Map<String, Object> membership(long userId) {
        var membership = db.one("SELECT * FROM membership WHERE user_id=?", userId);
        if (membership == null) throw new BusinessException(404, "MEMBERSHIP_NOT_FOUND", "会员记录不存在");
        return membership;
    }

    @Transactional
    public Map<String, Object> grantMembership(long operator, long userId, BenefitCommands.MembershipInput input) {
        validate(input, input == null ? null : input.reason());
        if (!input.validUntil().isAfter(input.validFrom())) throw invalid("会员结束时间必须晚于开始时间");
        lockUser(userId);
        var before = db.one("SELECT * FROM membership WHERE user_id=? FOR UPDATE", userId);
        if (before == null) {
            db.update("INSERT INTO membership(user_id,plan_code,valid_from,valid_until,status) VALUES(?,?,?,?,'ACTIVE')",
                    userId, input.planCode(), input.validFrom(), input.validUntil());
        } else {
            db.update("UPDATE membership SET plan_code=?,valid_from=?,valid_until=?,status='ACTIVE' WHERE user_id=?",
                    input.planCode(), input.validFrom(), input.validUntil(), userId);
        }
        // No write to member_quota: renewal/re-granting never creates two extra uses in the same month.
        var after = membership(userId);
        audit(operator, "MEMBERSHIP_GRANT", "User", userId, input.reason(), before, after);
        return after;
    }

    @Transactional
    public Map<String, Object> membershipStatus(long operator, long userId, BenefitCommands.StatusInput input) {
        validate(input, input == null ? null : input.reason());
        lockUser(userId);
        var before = db.one("SELECT * FROM membership WHERE user_id=? FOR UPDATE", userId);
        if (before == null) throw new BusinessException(404, "MEMBERSHIP_NOT_FOUND", "会员记录不存在");
        db.update("UPDATE membership SET status=? WHERE user_id=?", input.enabled() ? "ACTIVE" : "INACTIVE", userId);
        var after = membership(userId);
        audit(operator, "MEMBERSHIP_STATUS", "User", userId, input.reason(), before, after);
        return after;
    }

    private void lockUser(long userId) {
        if (db.one("SELECT id FROM iam_user WHERE id=? FOR UPDATE", userId) == null) {
            throw new BusinessException(404, "USER_NOT_FOUND", "用户不存在");
        }
    }

    private void audit(long operator, String action, String kind, long id, String reason, Object before, Object after) {
        String requestId = MDC.get("requestId");
        db.update("INSERT INTO ops_audit(operator_id,action,target_type,target_no,reason,before_json,after_json,request_id) VALUES(?,?,?,?,?,?,?,?)",
                operator, action, kind, String.valueOf(id), reason, before == null ? null : Json.write(before),
                Json.write(after), requestId == null ? UUID.randomUUID().toString() : requestId);
    }

    private <T> void validate(T input, String reason) {
        if (input == null || !validator.validate(input).isEmpty() || reason == null || reason.trim().length() < 5) throw invalid("运营参数或操作原因不合法");
    }

    private static BusinessException invalid(String message) { return new BusinessException(400, "INVALID_REQUEST", message); }
}
