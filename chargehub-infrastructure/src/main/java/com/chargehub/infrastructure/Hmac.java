package com.chargehub.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Exact five-line integration signature, separate from user authentication. */
@Component
public class Hmac {
    private final Db db;
    private final Environment environment;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public Hmac(Db db, Environment environment) { this(db, environment, Clock.systemUTC()); }
    Hmac(Db db, Environment environment, Clock clock) {
        this.db = db; this.environment = environment; this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void verify(HttpServletRequest request, byte[] body, String expectedKeyId) {
        String keyId = request.getHeader("X-Key-Id");
        String timestamp = request.getHeader("X-Timestamp");
        String nonce = request.getHeader("X-Nonce");
        String signature = request.getHeader("X-Signature");
        if (keyId == null || !keyId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
                || timestamp == null || nonce == null || signature == null)
            throw unauthorized();
        IntegrationKey integrationKey = key(keyId);
        if (!integrationKey.owner().equals(expectedOwner(expectedKeyId))) throw unauthorized();
        long seconds;
        byte[] provided;
        try {
            seconds = Long.parseLong(timestamp);
            if (!UUID.fromString(nonce).toString().equalsIgnoreCase(nonce)) throw unauthorized();
            provided = Base64.getDecoder().decode(signature);
        } catch (IllegalArgumentException e) { throw unauthorized(); }
        long now = clock.instant().getEpochSecond();
        if (seconds < now - 300 || seconds > now + 300) throw unauthorized();
        byte[] expected = sign(request.getMethod(), request.getRequestURI(), timestamp, nonce,
                body == null ? new byte[0] : body, integrationKey.secret());
        if (!MessageDigest.isEqual(expected, provided)) throw unauthorized();
        try {
            db.update("INSERT INTO integration_nonce(key_id,nonce,expires_at) VALUES(?,?,?)",
                    keyId, nonce, clock.instant().plusSeconds(660));
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "REPLAY_DETECTED", "签名 nonce 已使用，请用新的签名重试原业务编号");
        }
    }

    public Map<String, String> headers(String method, String path, byte[] body, String keyId) {
        if (!path.startsWith("/") || path.contains("?") || path.contains("\n"))
            throw new IllegalArgumentException("Signature path must be an absolute path without query");
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String signingId = Set.of("payment", "device", "control").contains(keyId)
                ? environment.getProperty("chargehub." + keyId + "-key-id", keyId) : keyId;
        return Map.of("X-Key-Id", signingId, "X-Timestamp", timestamp, "X-Nonce", nonce,
                "X-Signature", Base64.getEncoder().encodeToString(sign(method, path, timestamp, nonce,
                        body == null ? new byte[0] : body, key(signingId).secret())));
    }

    public Map<String, String> headers(String method, String path, String body, String keyId) {
        return headers(method, path, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8), keyId);
    }

    private record IntegrationKey(String owner, String secret) {}

    private String expectedOwner(String expectedKeyId) {
        for (String kind : List.of("payment", "device", "control")) {
            if (kind.equals(expectedKeyId) || expectedKeyId.equals(environment.getProperty("chargehub." + kind + "-key-id", kind)))
                return kind;
        }
        throw unauthorized();
    }

    private IntegrationKey key(String keyId) {
        if (keyId == null || !keyId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) throw unauthorized();
        // Register overlapping key IDs explicitly. Their owner prevents payment/device key substitution.
        String prefix = "chargehub.integration-keys." + keyId;
        String owner = environment.getProperty(prefix + ".owner");
        if (owner != null) {
            if (!Set.of("payment", "device", "control").contains(owner)) throw unauthorized();
            return new IntegrationKey(owner, configuredSecret(environment.getProperty(prefix + ".secret")));
        }
        for (String kind : List.of("payment", "device", "control")) {
            String configuredId = environment.getProperty("chargehub." + kind + "-key-id", kind);
            if (!configuredId.equals(keyId)) continue;
            String secret = environment.getProperty("chargehub." + kind + "-key");
            if (secret == null || secret.isBlank())
                secret = environment.getProperty("CHARGEHUB_" + kind.toUpperCase(Locale.ROOT) + "_KEY");
            return new IntegrationKey(kind, configuredSecret(secret));
        }
        throw unauthorized();
    }

    private String configuredSecret(String secret) {
        if (secret == null || secret.length() < 16)
            throw new BusinessException(503, "INTEGRATION_NOT_CONFIGURED", "集成密钥未配置或长度不足16字符");
        return secret;
    }

    static byte[] sign(String method, String path, String timestamp, String nonce, byte[] body, String secret) {
        String canonical = method.toUpperCase(Locale.ROOT) + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + Json.hash(body);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException("Cannot compute integration signature", e); }
    }

    private static BusinessException unauthorized() {
        return new BusinessException(401, "INVALID_SIGNATURE", "集成签名无效或已过期");
    }

    @Scheduled(fixedDelay = 600_000)
    public void removeExpiredNonces() { db.update("DELETE FROM integration_nonce WHERE expires_at < ?", clock.instant()); }
}
