package com.chargehub.benefit;

import com.chargehub.infrastructure.BusinessException;
import com.chargehub.infrastructure.Db;
import com.chargehub.infrastructure.Idempotency;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.function.Supplier;

/** Lock actor and target in global ID order before the standard idempotency actor lock. */
@Service
public class BenefitWriteCoordinator {
    private final Db db;
    private final Idempotency idempotency;

    public BenefitWriteCoordinator(Db db, Idempotency idempotency) {
        this.db = db;
        this.idempotency = idempotency;
    }

    @Transactional
    public Map<String, Object> membership(long actor, long target, String scope, String key,
                                          Map<String, Object> request, Supplier<Map<String, Object>> action) {
        lock(Math.min(actor, target), actor);
        if (actor != target) lock(Math.max(actor, target), actor);
        return idempotency.execute(actor, scope, key, request, action);
    }

    private void lock(long id, long actor) {
        if (db.one("SELECT id FROM iam_user WHERE id=? FOR UPDATE", id) == null) {
            throw new BusinessException(id == actor ? 401 : 404, id == actor ? "AUTH_REQUIRED" : "USER_NOT_FOUND",
                    id == actor ? "账号不存在" : "用户不存在");
        }
    }
}
