package com.chargehub.identity;

import com.chargehub.infrastructure.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class IdentityAdminService {
    private final Db db;
    private final Idempotency idempotency;

    public IdentityAdminService(Db db, Idempotency idempotency) {
        this.db = db;
        this.idempotency = idempotency;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> users(long actor, String cursor, int limit) {
        requireAdmin(actor);
        if (limit < 1 || limit > 100) throw IdentityValidation.invalid();
        long after = cursor == null ? 0 : IdentityValidation.positiveId(cursor);
        var rows = db.rows("SELECT id,login_name,enabled,created_at FROM iam_user WHERE id>? ORDER BY id LIMIT ?", after, limit + 1);
        boolean more = rows.size() > limit;
        var visible = rows.subList(0, Math.min(rows.size(), limit));
        List<Map<String, Object>> items = visible.stream().map(this::userView).toList();
        var page = new LinkedHashMap<String, Object>();
        page.put("items", items);
        page.put("nextCursor", more ? Db.str(visible.getLast(), "id") : null);
        return page;
    }

    @Transactional
    public Map<String, Object> changeRoles(long actor, long target, Set<String> desired, String reason, String key) {
        if (target <= 0 || desired == null || desired.isEmpty() || !IdentityValidation.ROLES.containsAll(desired))
            throw IdentityValidation.invalid();
        IdentityValidation.validateReason(reason);
        lockAdministration(actor, target);
        var request = Map.<String, Object>of("roles", new TreeSet<>(desired), "reason", reason);
        return idempotency.execute(actor, "identity.roles/" + target, key, request, () -> {
            var user = db.one("SELECT id,login_name,enabled,created_at FROM iam_user WHERE id=? FOR UPDATE", target);
            if (user == null) throw new BusinessException(404, "USER_NOT_FOUND", "用户不存在");
            if ("__monitor__".equalsIgnoreCase(Db.str(user, "login_name")))
                throw new BusinessException(409, "RESERVED_ACCOUNT", "监控保留账号的角色不能通过用户授权接口修改");
            Set<String> before = roles(target);
            boolean removesEnabledAdmin = Db.num(user, "enabled") == 1 && before.contains("ADMIN") && !desired.contains("ADMIN");
            if (removesEnabledAdmin && Db.num(db.one("SELECT COUNT(*) n FROM iam_user u JOIN iam_user_role r ON r.user_id=u.id "
                    + "WHERE u.enabled=1 AND u.login_name<>'__monitor__' AND r.role_code='ADMIN'"), "n") <= 1)
                throw new BusinessException(409, "LAST_ADMIN_REQUIRED", "必须保留至少一个可用管理员");
            db.update("DELETE FROM iam_user_role WHERE user_id=?", target);
            for (String role : new TreeSet<>(desired))
                db.update("INSERT INTO iam_user_role(user_id,role_code) VALUES(?,?)", target, role);
            audit(actor, "USER_ROLES_CHANGED", target, reason, Map.of("roles", before), Map.of("roles", desired));
            return userView(user);
        });
    }

    @Transactional
    public Map<String, Object> revokeTokens(long actor, long target, String reason, String key) {
        if (target <= 0) throw IdentityValidation.invalid();
        IdentityValidation.validateReason(reason);
        lockAdministration(actor, target);
        return idempotency.execute(actor, "identity.revoke/" + target, key, Map.of("reason", reason), () -> {
            if (db.one("SELECT id FROM iam_user WHERE id=? FOR UPDATE", target) == null)
                throw new BusinessException(404, "USER_NOT_FOUND", "用户不存在");
            int revoked = db.update("UPDATE auth_token SET revoked_at=UTC_TIMESTAMP(3) WHERE user_id=? AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP(3)", target);
            audit(actor, "USER_TOKENS_REVOKED", target, reason, Map.of(), Map.of("revokedCount", revoked));
            return Map.of("userId", Long.toString(target), "revokedCount", revoked);
        });
    }

    private void lockAdministration(long actor, long target) {
        if (db.one("SELECT id FROM iam_admin_guard WHERE id=1 FOR UPDATE") == null)
            throw new IllegalStateException("Administrator guard migration is missing");
        // Membership administration uses the same ascending user order. Acquire both before Idempotency
        // takes the actor lock, otherwise an IAM actor->target operation can deadlock with target->actor.
        lockUser(Math.min(actor, target), actor);
        if (actor != target) lockUser(Math.max(actor, target), actor);
        // Recheck after serialization and user locks; another request may have revoked this actor's ADMIN role.
        requireAdmin(actor);
    }

    private void lockUser(long user, long actor) {
        if (db.one("SELECT id FROM iam_user WHERE id=? FOR UPDATE", user) == null)
            throw new BusinessException(user == actor ? 403 : 404, user == actor ? "FORBIDDEN" : "USER_NOT_FOUND",
                    user == actor ? "无权执行此操作" : "用户不存在");
    }

    private void requireAdmin(long actor) {
        if (db.one("SELECT u.id FROM iam_user u JOIN iam_user_role r ON r.user_id=u.id "
                + "WHERE u.id=? AND u.enabled=1 AND u.login_name<>'__monitor__' AND r.role_code='ADMIN'", actor) == null)
            throw new BusinessException(403, "FORBIDDEN", "无权执行此操作");
    }

    private Set<String> roles(long user) {
        var values = new TreeSet<String>();
        db.rows("SELECT role_code FROM iam_user_role WHERE user_id=? ORDER BY role_code", user)
                .forEach(row -> values.add(Db.str(row, "role_code")));
        return values;
    }

    private Map<String, Object> userView(Map<String, Object> user) {
        return Map.of("id", Db.str(user, "id"), "username", Db.str(user, "login_name"),
                "enabled", Db.num(user, "enabled") == 1, "roles", roles(Db.num(user, "id")),
                "createdAt", Db.instant(user, "created_at").toString());
    }

    private void audit(long actor, String action, long target, String reason, Object before, Object after) {
        db.update("INSERT INTO ops_audit(operator_id,action,target_type,target_no,reason,before_json,after_json,request_id) "
                        + "VALUES(?,?,'USER',?,?,?,?,?)", actor, action, Long.toString(target), reason,
                Json.write(before), Json.write(after), ApiResponse.requestIdValue());
    }
}
