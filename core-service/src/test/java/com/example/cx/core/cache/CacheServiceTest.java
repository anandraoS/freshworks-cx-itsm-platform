package com.example.cx.core.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.cx.core.config.CxProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class CacheServiceTest {
    static final TypeReference<String> STR = new TypeReference<>() {};
    static final UUID A = UUID.randomUUID(), B = UUID.randomUUID();

    static CxProperties props() {
        return new CxProperties(null, null, null, null, null, new CxProperties.Cache(1000, 5));
    }

    // ------------------------------------------------------------------ Caffeine (local profile)

    @Test
    void caffeineHitsMissesAndCounters() {
        SimpleMeterRegistry m = new SimpleMeterRegistry();
        CaffeineCacheService c = new CaffeineCacheService(props(), m);
        AtomicInteger loads = new AtomicInteger();
        assertThat(c.getOrLoad(A, "dash:30", Duration.ofMinutes(1), STR, () -> "v" + loads.incrementAndGet())).isEqualTo("v1");
        assertThat(c.getOrLoad(A, "dash:30", Duration.ofMinutes(1), STR, () -> "v" + loads.incrementAndGet())).isEqualTo("v1");
        assertThat(loads).hasValue(1);
        assertThat(m.counter("cx.cache", "cache", "dash", "result", "miss").count()).isEqualTo(1);
        assertThat(m.counter("cx.cache", "cache", "dash", "result", "hit").count()).isEqualTo(1);
    }

    @Test
    void keysAreTenantPrefixedAndIsolated() {
        assertThat(CacheService.fullKey(A, "x")).isEqualTo("t:" + A + ":x");
        CaffeineCacheService c = new CaffeineCacheService(props(), new SimpleMeterRegistry());
        assertThat(c.getOrLoad(A, "same", Duration.ofMinutes(1), STR, () -> "tenant-A")).isEqualTo("tenant-A");
        assertThat(c.getOrLoad(B, "same", Duration.ofMinutes(1), STR, () -> "tenant-B")).isEqualTo("tenant-B");
        assertThat(c.getOrLoad(A, "same", Duration.ofMinutes(1), STR, () -> "reloaded")).isEqualTo("tenant-A");
        assertThatThrownBy(() -> CacheService.fullKey(null, "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ttlExpiresEntries() throws Exception {
        CaffeineCacheService c = new CaffeineCacheService(props(), new SimpleMeterRegistry());
        AtomicInteger loads = new AtomicInteger();
        c.getOrLoad(A, "short", Duration.ofMillis(60), STR, () -> "v" + loads.incrementAndGet());
        Thread.sleep(150);
        assertThat(c.getOrLoad(A, "short", Duration.ofMillis(60), STR, () -> "v" + loads.incrementAndGet())).isEqualTo("v2");
    }

    @Test
    void evictAndEvictByPrefix() {
        CaffeineCacheService c = new CaffeineCacheService(props(), new SimpleMeterRegistry());
        AtomicInteger n = new AtomicInteger();
        for (String k : List.of("analytics:dashboard:7", "analytics:dashboard:30", "categories:list"))
            c.getOrLoad(A, k, Duration.ofMinutes(1), STR, () -> "v" + n.incrementAndGet());
        c.getOrLoad(B, "analytics:dashboard:7", Duration.ofMinutes(1), STR, () -> "other-tenant");
        c.evictByPrefix(A, "analytics:");
        assertThat(c.getOrLoad(A, "analytics:dashboard:7", Duration.ofMinutes(1), STR, () -> "fresh")).isEqualTo("fresh");
        assertThat(c.getOrLoad(A, "analytics:dashboard:30", Duration.ofMinutes(1), STR, () -> "fresh")).isEqualTo("fresh");
        assertThat(c.getOrLoad(A, "categories:list", Duration.ofMinutes(1), STR, () -> "fresh")).isNotEqualTo("fresh"); // other prefix untouched
        assertThat(c.getOrLoad(B, "analytics:dashboard:7", Duration.ofMinutes(1), STR, () -> "fresh")).isEqualTo("other-tenant"); // other tenant untouched
        c.evict(A, "categories:list");
        assertThat(c.getOrLoad(A, "categories:list", Duration.ofMinutes(1), STR, () -> "fresh")).isEqualTo("fresh");
    }

    @Test
    void singleFlightPreventsAStampede() throws Exception {
        CaffeineCacheService c = new CaffeineCacheService(props(), new SimpleMeterRegistry());
        AtomicInteger loads = new AtomicInteger();
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) fs.add(pool.submit(() -> {
            go.await();
            return c.getOrLoad(A, "hot", Duration.ofMinutes(1), STR, () -> {
                loads.incrementAndGet();
                try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return "loaded";
            });
        }));
        go.countDown();
        for (Future<String> f : fs) assertThat(f.get()).isEqualTo("loaded");
        pool.shutdown();
        assertThat(loads).hasValue(1); // twelve concurrent misses, ONE database load
    }

    @Test
    void failuresAndNullsAreNotCached() {
        CaffeineCacheService c = new CaffeineCacheService(props(), new SimpleMeterRegistry());
        assertThatThrownBy(() -> c.getOrLoad(A, "boom", Duration.ofMinutes(1), STR, () -> { throw new IllegalStateException("db down"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(c.getOrLoad(A, "boom", Duration.ofMinutes(1), STR, () -> "recovered")).isEqualTo("recovered");
        assertThat(c.getOrLoad(A, "nul", Duration.ofMinutes(1), STR, () -> null)).isNull();
        assertThat(c.getOrLoad(A, "nul", Duration.ofMinutes(1), STR, () -> "now-present")).isEqualTo("now-present");
    }

    @Test
    void loaderMayUseTheCacheItself() {
        CaffeineCacheService c = new CaffeineCacheService(props(), new SimpleMeterRegistry());
        String v = c.getOrLoad(A, "outer", Duration.ofMinutes(1), STR,
                () -> c.getOrLoad(A, "inner", Duration.ofMinutes(1), STR, () -> "x") + "-y");
        assertThat(v).isEqualTo("x-y"); // nested loads do not deadlock / hit recursive-update errors
    }

    // ------------------------------------------------------------------ Redis (docker profile) with Redis simulated down

    @SuppressWarnings("unchecked")
    @Test
    void redisDownFallsBackToTheLoaderAndTripsTheBreaker() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("Connection refused (simulated)"));
        SimpleMeterRegistry m = new SimpleMeterRegistry();
        RedisCacheService c = new RedisCacheService(redis, new ObjectMapper(), m, props());

        // every call still returns the correct value (straight from the loader) - the cache is only an optimisation
        assertThat(c.getOrLoad(A, "dash", Duration.ofSeconds(60), STR, () -> "from-db-1")).isEqualTo("from-db-1");
        assertThat(c.getOrLoad(A, "dash", Duration.ofSeconds(60), STR, () -> "from-db-2")).isEqualTo("from-db-2");
        assertThat(m.counter("cx.cache.fallback", "op", "read").count()).isGreaterThanOrEqualTo(1);
        // breaker open: the dead Redis is not even contacted again during the back-off window (no timeout per request)
        verify(ops, times(1)).get(anyString());
        verify(ops, never()).set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
        // evictions while Redis is down never throw either
        c.evict(A, "dash");
        c.evictByPrefix(A, "analytics:");
    }

    @SuppressWarnings("unchecked")
    @Test
    void redisHitsAreTenantKeyedJsonAndCounted() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("t:" + A + ":settings")).thenReturn("\"cached-json\"");
        SimpleMeterRegistry m = new SimpleMeterRegistry();
        RedisCacheService c = new RedisCacheService(redis, new ObjectMapper(), m, props());
        assertThat(c.getOrLoad(A, "settings", Duration.ofMinutes(1), STR, () -> "should-not-load")).isEqualTo("cached-json");
        assertThat(m.counter("cx.cache", "cache", "settings", "result", "hit").count()).isEqualTo(1);
        // miss: loader value is written with a TTL under the tenant-prefixed key
        when(ops.get("t:" + B + ":settings")).thenReturn(null);
        assertThat(c.getOrLoad(B, "settings", Duration.ofMinutes(1), STR, () -> "loaded")).isEqualTo("loaded");
        verify(ops).set(org.mockito.ArgumentMatchers.eq("t:" + B + ":settings"), org.mockito.ArgumentMatchers.eq("\"loaded\""), org.mockito.ArgumentMatchers.any(Duration.class));
        assertThat(m.counter("cx.cache", "cache", "settings", "result", "miss").count()).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    @Test
    void redisWriteFailureStillReturnsTheLoadedValue() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(null);
        org.mockito.Mockito.doThrow(new QueryTimeoutException("timeout (simulated)")).when(ops)
                .set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
        RedisCacheService c = new RedisCacheService(redis, new ObjectMapper(), new SimpleMeterRegistry(), props());
        assertThat(c.getOrLoad(A, "k", Duration.ofMinutes(1), STR, () -> "value")).isEqualTo("value");
    }
}
