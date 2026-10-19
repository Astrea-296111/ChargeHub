package com.chargehub.identity;

import com.chargehub.infrastructure.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

/** Database-clock windows and row locks enforce the same budgets across API instances. */
@Service
public class LoginAttemptLimiter {
    private static final long WINDOW_SECONDS = 60;
    private final Db db;
    private final TransactionTemplate transaction;
    private final int accountLimit;
    private final int addressLimit;

    public LoginAttemptLimiter(Db db, PlatformTransactionManager manager, Environment environment) {
        this.db = db;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.accountLimit = limit(environment, "chargehub.auth.login-account-limit", 10);
        this.addressLimit = limit(environment, "chargehub.auth.login-address-limit", 60);
    }

    private static int limit(Environment environment, String name, int fallback) {
        int value = environment.getProperty(name, Integer.class, fallback);
        if (value < 1 || value > 100_000) throw new IllegalArgumentException("Invalid login limit configuration");
        return value;
    }

    public void check(String username, String sourceAddress) {
        String canonical = Normalizer.normalize(username, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        String address = sourceAddress == null ? "unknown" : sourceAddress;
        boolean accepted = Boolean.TRUE.equals(transaction.execute(status -> {
            Instant now = Db.instant(db.one("SELECT UTC_TIMESTAMP(3) now"), "now");
            // Always address then account: disjoint bucket namespaces give every instance the same lock order.
            // Stop at an exhausted address budget so random usernames cannot create unbounded rows from one source.
            if (!consume(Json.hash("address:" + address), addressLimit, now)) return false;
            // Bind known accounts to the database ID so every collation-equivalent login name shares its budget.
            var account = db.one("SELECT id FROM iam_user WHERE login_name=?", username);
            String accountKey = account == null ? "account:" + canonical : "account-id:" + Db.num(account, "id");
            return consume(Json.hash(accountKey), accountLimit, now);
        }));
        // Throw after the independent transaction commits: rejected login never resets its budget.
        if (!accepted) throw new BusinessException(429, "LOGIN_RATE_LIMITED", "登录尝试过于频繁，请稍后重试");
    }

    private boolean consume(String key, int limit, Instant now) {
        db.update("INSERT INTO auth_login_bucket(bucket_key,window_started_at,attempt_count,expires_at) "
                        + "VALUES(?,?,0,?) ON DUPLICATE KEY UPDATE bucket_key=bucket_key",
                key, now, now.plusSeconds(WINDOW_SECONDS * 2));
        var bucket = db.one("SELECT * FROM auth_login_bucket WHERE bucket_key=? FOR UPDATE", key);
        boolean expired = !Db.instant(bucket, "window_started_at").isAfter(now.minusSeconds(WINDOW_SECONDS));
        long count = expired ? 0 : Db.num(bucket, "attempt_count");
        db.update("UPDATE auth_login_bucket SET window_started_at=?,attempt_count=?,expires_at=? WHERE bucket_key=?",
                expired ? now : Db.instant(bucket, "window_started_at"), Math.min(count + 1, 100_001),
                now.plusSeconds(WINDOW_SECONDS * 2), key);
        return count < limit;
    }

    @Scheduled(fixedDelay = 300_000)
    public void removeExpiredBuckets() {
        db.update("DELETE FROM auth_login_bucket WHERE expires_at<UTC_TIMESTAMP(3) LIMIT 1000");
        db.update("DELETE FROM auth_token WHERE expires_at<TIMESTAMPADD(DAY,-7,UTC_TIMESTAMP(3)) LIMIT 1000");
    }
}
