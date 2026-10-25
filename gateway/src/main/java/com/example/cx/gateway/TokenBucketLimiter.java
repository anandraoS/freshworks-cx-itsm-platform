package com.example.cx.gateway;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * In-memory token bucket per key. Not distributed: with N gateway replicas the effective limit is N x capacity
 * (use Redis or an edge limiter when a global limit is needed). Idle, full buckets are evicted when the map grows
 * beyond {@code maxKeys} so a key-flood cannot exhaust memory.
 */
public class TokenBucketLimiter {
    public record Decision(boolean allowed, long retryAfterSeconds) { }

    private static final class Bucket {
        double tokens;
        long lastNanos;
        Bucket(double tokens, long lastNanos) { this.tokens = tokens; this.lastNanos = lastNanos; }
    }

    private final int capacity;
    private final double refillPerSecond;
    private final int maxKeys;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketLimiter(int capacity, double refillPerSecond, int maxKeys, LongSupplier nanoClock) {
        if (capacity < 1 || refillPerSecond <= 0) throw new IllegalArgumentException("capacity>=1 and refill>0 required");
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.maxKeys = maxKeys;
        this.nanoClock = nanoClock;
    }

    public Decision tryAcquire(String key) {
        long now = nanoClock.getAsLong();
        if (buckets.size() > maxKeys) evict(now);
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        synchronized (b) {
            double elapsedSec = Math.max(0, now - b.lastNanos) / 1_000_000_000.0;
            b.tokens = Math.min(capacity, b.tokens + elapsedSec * refillPerSecond);
            b.lastNanos = now;
            if (b.tokens >= 1.0) {
                b.tokens -= 1.0;
                return new Decision(true, 0);
            }
            long wait = (long) Math.ceil((1.0 - b.tokens) / refillPerSecond);
            return new Decision(false, Math.max(1, wait));
        }
    }

    private void evict(long now) {
        buckets.entrySet().removeIf(e -> {
            Bucket b = e.getValue();
            synchronized (b) {
                double t = Math.min(capacity, b.tokens + (now - b.lastNanos) / 1e9 * refillPerSecond);
                return t >= capacity;
            }
        });
    }

    public int trackedKeys() { return buckets.size(); }
}
