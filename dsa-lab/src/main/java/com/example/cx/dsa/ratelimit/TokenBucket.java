package com.example.cx.dsa.ratelimit;

import java.util.HashMap;
import java.util.Map;

/**
 * Token bucket: allows bursts up to {@code capacity}, sustained rate {@code refillPerSecond}.
 *
 * <p><b>Purpose / platform use case:</b> USED BY THE PLATFORM in the same form: gateway {@code TokenBucketLimiter} (per user/IP,
 * 429 {@code RATE_LIMITED}) and core-service {@code LoginRateLimiter} (login/registration brute-force protection).
 *
 * <p><b>Algorithm:</b> lazy refill: on each call add {@code elapsed * rate} tokens (capped at capacity), then take one if
 * available. No timer thread is needed.
 *
 * <p><b>Complexity:</b> O(1) time per request, O(keys) space (production code bounds the key map to avoid memory growth).
 *
 * <p><b>Edge cases:</b> first request (bucket starts full), clock going backwards (clamp elapsed at 0), fractional tokens,
 * long idle time (cap at capacity), many distinct keys (unbounded growth, evict idle full buckets).
 *
 * <p><b>Brute force vs optimized:</b> a timer thread adding tokens costs a thread and drifts; lazy refill is exact and free.
 *
 * <p><b>Interview follow-ups:</b> distributed version (Redis Lua script for atomicity)? Token bucket vs leaky bucket (smooths
 * output vs permits bursts)? Returning Retry-After? Cost-weighted requests (take n tokens)?
 */
public class TokenBucket implements RateLimiter {
    private static final class State {
        double tokens;
        long lastMillis;
    }

    private final int capacity;
    private final double refillPerMilli;
    private final Map<String, State> states = new HashMap<>();

    public TokenBucket(int capacity, double refillPerSecond) {
        if (capacity < 1 || refillPerSecond <= 0) throw new IllegalArgumentException("capacity>=1, refill>0");
        this.capacity = capacity;
        this.refillPerMilli = refillPerSecond / 1000.0;
    }

    @Override
    public synchronized boolean tryAcquire(String key, long nowMillis) {
        State s = states.get(key);
        if (s == null) {
            s = new State();
            s.tokens = capacity;
            s.lastMillis = nowMillis;
            states.put(key, s);
        }
        long elapsed = Math.max(0, nowMillis - s.lastMillis);
        s.tokens = Math.min(capacity, s.tokens + elapsed * refillPerMilli);
        s.lastMillis = Math.max(s.lastMillis, nowMillis);
        if (s.tokens >= 1.0) {
            s.tokens -= 1.0;
            return true;
        }
        return false;
    }
}
