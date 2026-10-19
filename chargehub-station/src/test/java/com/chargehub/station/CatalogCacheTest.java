package com.chargehub.station;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatalogCacheTest {
    @AfterEach void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void prePublicationReaderCannotRepopulateTheNewGenerationWithOldPrices() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        Map<String, String> store = new ConcurrentHashMap<>();
        store.put("test:generation", "1");
        when(values.get(anyString())).thenAnswer(call -> store.get(call.getArgument(0)));
        doAnswer(call -> { store.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(eq("test:generation"), anyString());
        doAnswer(call -> { store.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        var cache = new CatalogCache(redis, "test:", 30, 10, 1000, null);
        assertEquals(80, cache.read("tariff", () -> {
            cache.invalidateAfterCommit(); // Publishing commits while the old database reader is in flight.
            return Map.of("energyRate", 80);
        }).get("energyRate"));
        assertTrue(store.containsKey("test:1:tariff"));
        String newGeneration = store.get("test:generation");
        assertNotEquals("1", newGeneration);
        assertEquals(120, cache.read("tariff", () -> Map.of("energyRate", 120)).get("energyRate"));
        assertTrue(store.containsKey("test:" + newGeneration + ":tariff"));
        assertEquals(120, cache.read("tariff", () -> fail("New generation should be cached")).get("energyRate"));
    }

    @Test
    void invalidationRunsOnlyAfterCommitAndTransactionalReadsBypassRedis() {
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var cache = new CatalogCache(redis, "test:", 30, 0, 1000, null);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        cache.invalidateAfterCommit();
        assertEquals("uncommitted", cache.read("station", () -> Map.of("value", "uncommitted")).get("value"));
        verifyNoInteractions(values);
        var synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(s -> s.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(values);
        synchronizations.forEach(s -> s.afterCommit());
        verify(values).set(eq("test:generation"), anyString());
    }

    @Test
    void unavailableRedisCannotFailReadsAndCooldownAvoidsRepeatedBlockingCalls() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("test outage"));
        var cache = new CatalogCache(redis, "test:", 30, 0, 30000, null);
        AtomicInteger queries = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            assertEquals("database", cache.read("station", () -> {
                queries.incrementAndGet(); return Map.of("source", "database");
            }).get("source"));
        }
        assertEquals(10, queries.get());
        verify(redis, times(1)).opsForValue();
        assertDoesNotThrow(cache::invalidateAfterCommit);
    }
}
