package com.example.cx.core.security;

import com.example.cx.core.common.RateLimitedException;
import com.example.cx.core.config.CxProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * Token-bucket limiter for login / registration. Memory is bounded: at most {@code maxTrackedKeys} buckets are kept
 * (Caffeine size eviction + idle expiry), so an attacker rotating keys cannot grow the heap without limit.
 * Keys: "ip:<addr>" and "acct:<tenantSlug>:<email>" (both must have a token). Per-node (not distributed) by design;
 * the gateway is the place for a global limit.
 */
@Component
public class LoginRateLimiter {
    private final int capacity;
    private final double refillPerNano;
    private final LongSupplier nanoClock;
    private final Cache<String, Bucket> buckets;

    @org.springframework.beans.factory.annotation.Autowired
    public LoginRateLimiter(CxProperties props) {
        this(props.security().loginRate().capacity(), props.security().loginRate().refillPerMinute(),
                props.security().loginRate().maxTrackedKeys(), System::nanoTime);
    }

    public LoginRateLimiter(int capacity, int refillPerMinute, int maxKeys, LongSupplier nanoClock) {
        this.capacity = capacity;
        this.refillPerNano = refillPerMinute / 60_000_000_000.0;
        this.nanoClock = nanoClock;
        this.buckets = Caffeine.newBuilder().maximumSize(maxKeys).expireAfterAccess(Duration.ofMinutes(30)).build();
    }

    /** Consumes one token from every key or throws RateLimitedException. */
    public void check(String... keys) {
        for (String k : keys) {
            Bucket b = buckets.get(k, x -> new Bucket(capacity, nanoClock.getAsLong()));
            long waitSeconds = b.tryConsume(nanoClock.getAsLong(), capacity, refillPerNano);
            if (waitSeconds > 0) throw new RateLimitedException("Too many attempts; try again later", waitSeconds);
        }
    }

    public long trackedKeys() { buckets.cleanUp(); return buckets.estimatedSize(); }

    private static final class Bucket {
        private double tokens;
        private long last;

        Bucket(int capacity, long now) { this.tokens = capacity; this.last = now; }

        synchronized long tryConsume(long now, int capacity, double refillPerNano) {
            tokens = Math.min(capacity, tokens + (now - last) * refillPerNano);
            last = now;
            if (tokens >= 1) { tokens -= 1; return 0; }
            return (long) Math.ceil((1 - tokens) / refillPerNano / 1_000_000_000.0);
        }
    }
}
