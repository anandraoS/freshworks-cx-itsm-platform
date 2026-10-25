package com.example.cx.core.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * EXTENSION POINT: tenant-keyed cache-aside. Every key is stored as {@code t:{tenantId}:{key}} so one tenant can never
 * read another tenant's entry, and a tenant's entries can be dropped by prefix. Implementations: Caffeine (local
 * profile) and Redis (docker profile). The cache is an optimisation only: callers must tolerate loader-only behaviour
 * (that is exactly what happens when Redis is down).
 *
 * Contract: {@code loader} is invoked at most once per key per node at a time (single-flight) so a hot expired key does
 * not stampede the database. Values must be Jackson-serialisable (records/DTOs), never entities.
 */
public interface CacheService {

    <T> T getOrLoad(UUID tenantId, String key, Duration ttl, TypeReference<T> type, Supplier<T> loader);

    void evict(UUID tenantId, String key);

    /** Evict every entry of the tenant whose key (without the tenant prefix) starts with {@code keyPrefix}. */
    void evictByPrefix(UUID tenantId, String keyPrefix);

    static String fullKey(UUID tenantId, String key) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId required for cache key");
        return "t:" + tenantId + ":" + key;
    }
}
