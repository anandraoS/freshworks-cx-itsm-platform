package com.example.cx.core.cache;

import com.example.cx.core.config.CxProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis implementation (docker profile). JSON values with TTL (+-10% jitter so keys written together do not expire
 * together). Single-flight is per node (a CompletableFuture per key); across nodes the worst case is one load per node
 * per TTL, which is acceptable for 60s dashboards.
 *
 * GRACEFUL DEGRADATION: any Redis failure is swallowed, counted ({@code cx.cache.fallback}) and the loader result is
 * returned directly. After a failure Redis is skipped for {@code redis-failure-backoff-seconds} (a minimal circuit
 * breaker) so a dead Redis does not add a connect-timeout to every request.
 */
@Component
@Profile("docker")
public class RedisCacheService implements CacheService {
    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final long backoffNanos;
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();
    private volatile long skipRedisUntilNanos = 0;

    public RedisCacheService(StringRedisTemplate redis, ObjectMapper mapper, MeterRegistry meters, CxProperties props) {
        this.redis = redis;
        this.mapper = mapper;
        this.meters = meters;
        this.backoffNanos = Duration.ofSeconds(props.cache().redisFailureBackoffSeconds()).toNanos();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(UUID tenantId, String key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
        String full = CacheService.fullKey(tenantId, key);
        String name = CaffeineCacheService.name(key);
        T cached = readQuietly(full, type);
        if (cached != null) {
            meters.counter("cx.cache", "cache", name, "result", "hit").increment();
            return cached;
        }
        meters.counter("cx.cache", "cache", name, "result", "miss").increment();

        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> existing = inflight.putIfAbsent(full, mine);
        if (existing != null) { // someone on this node is already loading: wait for their result
            try { return (T) existing.join(); } catch (RuntimeException e) { return loader.get(); }
        }
        try {
            T v = loader.get();
            if (v != null) writeQuietly(full, v, ttl);
            mine.complete(v);
            return v;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(full, mine);
        }
    }

    @Override
    public void evict(UUID tenantId, String key) {
        guarded(() -> redis.delete(CacheService.fullKey(tenantId, key)));
    }

    @Override
    public void evictByPrefix(UUID tenantId, String keyPrefix) {
        String pattern = CacheService.fullKey(tenantId, keyPrefix) + "*";
        guarded(() -> redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) conn -> {
            try (Cursor<byte[]> c = conn.keyCommands().scan(ScanOptions.scanOptions().match(pattern).count(200).build())) {
                while (c.hasNext()) conn.keyCommands().del(c.next());
            }
            return null;
        }));
    }

    private <T> T readQuietly(String full, TypeReference<T> type) {
        if (redisSkipped()) { meters.counter("cx.cache.fallback", "op", "read").increment(); return null; }
        try {
            String json = redis.opsForValue().get(full);
            return json == null ? null : mapper.readValue(json, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Dropping undecodable cache entry {}", full);
            guarded(() -> redis.delete(full));
            return null;
        } catch (RuntimeException e) {
            tripBreaker("read", e);
            return null;
        }
    }

    private void writeQuietly(String full, Object v, Duration ttl) {
        if (redisSkipped()) return;
        try {
            long jitter = (long) (ttl.toMillis() * 0.1 * ThreadLocalRandom.current().nextDouble(-1, 1));
            redis.opsForValue().set(full, mapper.writeValueAsString(v), Duration.ofMillis(ttl.toMillis() + jitter));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Value for {} is not JSON-serialisable; not cached", full);
        } catch (RuntimeException e) {
            tripBreaker("write", e);
        }
    }

    private void guarded(Runnable r) {
        if (redisSkipped()) return;
        try { r.run(); } catch (RuntimeException e) { tripBreaker("evict", e); }
    }

    private boolean redisSkipped() { return System.nanoTime() < skipRedisUntilNanos; }

    private void tripBreaker(String op, RuntimeException e) {
        meters.counter("cx.cache.fallback", "op", op).increment();
        skipRedisUntilNanos = System.nanoTime() + backoffNanos;
        log.warn("Redis unavailable ({}); serving without cache for {}s: {}", op, backoffNanos / 1_000_000_000L,
                e.getClass().getSimpleName());
    }
}
