package com.example.cx.core.cache;

import com.example.cx.core.config.CxProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * In-process implementation (local profile). TTL is per entry; size is bounded by {@code cx.cache.max-entries}.
 * Single-flight is implemented with one CompletableFuture per in-flight key instead of Caffeine's compute-in-map: the
 * loader runs OUTSIDE any map lock, so a loader that itself uses the cache (nested keys) cannot hit a recursive-update
 * error or deadlock.
 */
@Component
@Profile("!docker")
public class CaffeineCacheService implements CacheService {
    private record Entry(Object value, long ttlNanos) {}

    private final Cache<String, Entry> cache;
    private final MeterRegistry meters;
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();

    public CaffeineCacheService(CxProperties props, MeterRegistry meters) {
        this.meters = meters;
        this.cache = Caffeine.newBuilder()
                .maximumSize(props.cache().maxEntries())
                .expireAfter(new Expiry<String, Entry>() {
                    public long expireAfterCreate(String k, Entry v, long now) { return v.ttlNanos(); }
                    public long expireAfterUpdate(String k, Entry v, long now, long cur) { return v.ttlNanos(); }
                    public long expireAfterRead(String k, Entry v, long now, long cur) { return cur; }
                }).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(UUID tenantId, String key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
        String full = CacheService.fullKey(tenantId, key);
        String name = name(key);
        Entry e = cache.getIfPresent(full);
        if (e != null) {
            meters.counter("cx.cache", "cache", name, "result", "hit").increment();
            return (T) e.value();
        }
        meters.counter("cx.cache", "cache", name, "result", "miss").increment();

        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> existing = inflight.putIfAbsent(full, mine);
        if (existing != null) { // another thread is loading this key: wait for its result (stampede protection)
            try { return (T) existing.join(); } catch (RuntimeException ex) { return loader.get(); }
        }
        try {
            T v = loader.get();
            if (v != null) cache.put(full, new Entry(v, ttl.toNanos()));
            mine.complete(v);
            return v;
        } catch (RuntimeException ex) {
            mine.completeExceptionally(ex);
            throw ex;
        } finally {
            inflight.remove(full, mine);
        }
    }

    @Override
    public void evict(UUID tenantId, String key) {
        cache.invalidate(CacheService.fullKey(tenantId, key));
    }

    @Override
    public void evictByPrefix(UUID tenantId, String keyPrefix) {
        String p = CacheService.fullKey(tenantId, keyPrefix);
        cache.asMap().keySet().removeIf(k -> k.startsWith(p));
    }

    static String name(String key) {
        int i = key.indexOf(':');
        return i < 0 ? key : key.substring(0, i);
    }
}
