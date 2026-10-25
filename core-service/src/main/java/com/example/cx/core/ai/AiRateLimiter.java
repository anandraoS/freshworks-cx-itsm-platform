package com.example.cx.core.ai;

import com.example.cx.core.common.RateLimitedException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Per-tenant fixed-window limiter for analyze calls (default 30 / minute / tenant / node). One noisy tenant cannot exhaust the
 * provider budget for the others. Bounded memory: Caffeine maximumSize + expireAfterWrite (the window itself). Per node; a global
 * limit belongs in the gateway.
 */
@Component
public class AiRateLimiter {
    private record Window(long startMs, AtomicInteger count) {}

    private final AiSettings settings;
    private final Cache<UUID, Window> windows = Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(1)).build();

    public AiRateLimiter(AiSettings settings) { this.settings = settings; }

    public void check(UUID tenantId) {
        Window w = windows.get(tenantId, k -> new Window(System.currentTimeMillis(), new AtomicInteger()));
        if (w.count().incrementAndGet() > settings.ratePerMinute) {
            long retry = Math.max(1, (60_000 - (System.currentTimeMillis() - w.startMs())) / 1000);
            throw new RateLimitedException("AI analysis rate limit reached for this organization", retry);
        }
    }

    public void reset() { windows.invalidateAll(); }
}
