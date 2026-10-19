package com.chargehub.identity;

import com.chargehub.infrastructure.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

@Service
public class SessionRevocationService {
    private final Db db;
    private final Idempotency idempotency;

    public SessionRevocationService(Db db, Idempotency idempotency) {
        this.db = db;
        this.idempotency = idempotency;
    }

    @Transactional
    public Map<String, Object> logout(long user, String token, boolean allSessions, String reason, String key) {
        IdentityValidation.validateReason(reason);
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}"))
            throw new BusinessException(401, "UNAUTHENTICATED", "请先登录");
        String digest = Json.hash(token);
        var request = Map.<String, Object>of("allSessions", allSessions, "reason", reason, "credentialDigest", digest);
        return idempotency.execute(user, "auth.logout", key, request, () -> {
            int count = allSessions
                    ? db.update("UPDATE auth_token SET revoked_at=UTC_TIMESTAMP(3) WHERE user_id=? AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP(3)", user)
                    : db.update("UPDATE auth_token SET revoked_at=UTC_TIMESTAMP(3) WHERE user_id=? AND token_hash=? AND revoked_at IS NULL", user, digest);
            db.update("INSERT INTO ops_audit(operator_id,action,target_type,target_no,reason,before_json,after_json,request_id) "
                            + "VALUES(?,'USER_LOGOUT','USER',?,?,?, ?,?)", user, Long.toString(user), reason,
                    Json.write(Map.of()), Json.write(Map.of("allSessions", allSessions, "revokedCount", count)), ApiResponse.requestIdValue());
            return Map.of("revoked", true, "allSessions", allSessions, "revokedCount", count);
        });
    }
}
