package com.chargehub.identity;

import com.chargehub.infrastructure.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Short-lived opaque bearer tokens. Only SHA-256 digests are stored in MySQL. */
@Service
public class TokenService {
    private static final int TTL_SECONDS = 1800;
    private final Db db;
    private final PasswordEncoder passwords;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;
    private final ConcurrentHashMap<String, Attempts> attempts = new ConcurrentHashMap<>();
    private record Attempts(long window, int count) {}

    public TokenService(Db db, PasswordEncoder passwords) {
        this.db = db; this.passwords = passwords;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public Map<String, Object> login(String username, String password, String sourceAddress) {
        if (username == null || username.isBlank() || username.length() > 80 || password == null || password.length() > 128)
            throw new BusinessException(400, "INVALID_REQUEST", "用户名或密码格式不正确");
        rateLimit(username, sourceAddress);
        var user = db.one("SELECT id,login_name,password_hash,enabled FROM iam_user WHERE login_name=?", username);
        String hash = user == null ? dummyHash : Db.str(user, "password_hash");
        boolean matches = passwords.matches(password, hash);
        if (user == null || !matches || Db.num(user, "enabled") != 1)
            throw new BusinessException(401, "INVALID_CREDENTIALS", "用户名或密码不正确");
        long userId = Db.num(user, "id");
        Set<String> roles = roles(userId);
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("INSERT INTO auth_token(token_hash,user_id,expires_at) VALUES(?,?,?)",
                Json.hash(token), userId, Instant.now().plusSeconds(TTL_SECONDS));
        return Map.of("accessToken", token, "tokenType", "Bearer", "expiresIn", TTL_SECONDS, "roles", roles);
    }

    public UserIdentity resolve(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        var user = db.one("SELECT u.id,u.login_name FROM auth_token t JOIN iam_user u ON u.id=t.user_id "
                + "WHERE t.token_hash=? AND t.expires_at>? AND u.enabled=1", Json.hash(token), Instant.now());
        return user == null ? null : new UserIdentity(Db.num(user, "id"), Db.str(user, "login_name"), roles(Db.num(user, "id")));
    }

    private Set<String> roles(long userId) {
        var roles = new TreeSet<String>();
        db.rows("SELECT role_code FROM iam_user_role WHERE user_id=?", userId)
                .forEach(row -> roles.add(Db.str(row, "role_code")));
        return Collections.unmodifiableSet(roles);
    }

    private void rateLimit(String username, String address) {
        long now = Instant.now().getEpochSecond();
        if (attempts.size() > 10_000) attempts.entrySet().removeIf(entry -> entry.getValue().window < now - 60);
        String key = Json.hash(address + ":" + username.toLowerCase(Locale.ROOT));
        Attempts state = attempts.compute(key, (ignored, old) -> old == null || old.window < now - 60
                ? new Attempts(now, 1) : new Attempts(old.window, old.count + 1));
        if (state.count > 10) throw new BusinessException(429, "LOGIN_RATE_LIMITED", "登录尝试过于频繁，请稍后重试");
    }
}
