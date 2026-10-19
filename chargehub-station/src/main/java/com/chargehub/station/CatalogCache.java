package com.chargehub.station;

import com.chargehub.infrastructure.Json;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Best-effort display cache. Neither write eligibility nor inventory is ever decided here. */
public class CatalogCache {
    private static final Logger LOG = LoggerFactory.getLogger(CatalogCache.class);
    private final StringRedisTemplate redis;
    private final String prefix;
    private final int ttlSeconds;
    private final int jitterSeconds;
    private final int cooldownMillis;
    private final MeterRegistry metrics;
    private final AtomicLong unavailableUntil = new AtomicLong();

    public CatalogCache(StringRedisTemplate redis, String prefix, int ttlSeconds, int jitterSeconds,
                        int cooldownMillis, MeterRegistry metrics) {
        this.redis = redis;
        this.prefix = prefix;
        this.ttlSeconds = ttlSeconds;
        this.jitterSeconds = jitterSeconds;
        this.cooldownMillis = cooldownMillis;
        this.metrics = metrics;
    }

    public static CatalogCache disabled() {
        return new CatalogCache(null, "disabled:", 30, 5, 1000, null);
    }

    public Map<String, Object> read(String resource, Supplier<Map<String, Object>> database) {
        // Also prevents an uncommitted transaction from leaking its own writes to another request.
        if (redis == null || TransactionSynchronizationManager.isActualTransactionActive()
                || System.nanoTime() < unavailableUntil.get()) {
            count("bypass");
            return database.get();
        }
        String generation;
        String key;
        try {
            generation = redis.opsForValue().get(prefix + "generation");
            if (generation == null) {
                redis.opsForValue().setIfAbsent(prefix + "generation", UUID.randomUUID().toString());
                generation = redis.opsForValue().get(prefix + "generation");
                if (generation == null) throw new IllegalStateException("Cache namespace initialization unavailable");
            }
            key = prefix + generation + ":" + resource;
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                try {
                    var result = Json.read(cached);
                    count("hit");
                    return result;
                } catch (RuntimeException corruptEntry) {
                    redis.delete(key);
                    count("corrupt");
                }
            }
        } catch (RuntimeException unavailable) {
            degraded();
            return database.get();
        }
        count("miss");
        var value = database.get();
        try {
            String serialized = Json.write(value);
            // Do not let one very large catalogue entry exhaust the shared cache.
            if (serialized.length() <= 262144) {
                int jitter = jitterSeconds == 0 ? 0 : ThreadLocalRandom.current().nextInt(jitterSeconds + 1);
                redis.opsForValue().set(key, serialized, Duration.ofSeconds(ttlSeconds + jitter));
            }
        } catch (RuntimeException unavailable) {
            degraded();
        }
        return value;
    }

    /** A namespace generation invalidates every page without KEYS, SCAN or a race-prone key list. */
    public void invalidateAfterCommit() {
        if (redis == null) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { invalidateNow(); }
            });
        } else {
            invalidateNow();
        }
    }

    private void invalidateNow() {
        try {
            // Random namespaces are never reused even after FLUSHDB, Redis restore or restart.
            redis.opsForValue().set(prefix + "generation", UUID.randomUUID().toString());
            unavailableUntil.set(0);
            count("invalidated");
        } catch (RuntimeException unavailable) {
            degraded();
        }
    }

    private void degraded() {
        long now = System.nanoTime();
        long previous = unavailableUntil.getAndSet(now + Duration.ofMillis(cooldownMillis).toNanos());
        if (previous <= now) LOG.warn("Catalogue Redis unavailable; reads fall back to MySQL");
        count("fallback");
    }

    private void count(String outcome) {
        if (metrics != null) metrics.counter("chargehub.catalog.cache", "outcome", outcome).increment();
    }
}
