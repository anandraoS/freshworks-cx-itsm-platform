package com.example.cx.dsa.ratelimit;

/**
 * Common contract for the four limiter algorithms. Time is injected as epoch milliseconds so behaviour is deterministic in tests.
 */
public interface RateLimiter {
    /** @return true when the request identified by {@code key} may proceed at {@code nowMillis} */
    boolean tryAcquire(String key, long nowMillis);
}
