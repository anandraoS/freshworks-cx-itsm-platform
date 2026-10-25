package com.example.cx.dsa.ratelimit;

import java.util.HashMap;
import java.util.Map;

/**
 * Sliding window counter: approximates a rolling window using two fixed windows weighted by overlap.
 *
 * <p><b>Purpose / platform use case:</b> EDUCATIONAL. The usual production compromise for per-tenant API quotas: near-exact
 * limits with O(1) memory per key (used by several CDNs and API gateways).
 *
 * <p><b>Algorithm:</b> estimate = {@code previousCount * (1 - fractionOfCurrentWindowElapsed) + currentCount}. Accept if
 * {@code estimate + 1 <= limit}. When the window index advances by exactly one the current count becomes the previous one;
 * if more than one window passed both are zero.
 *
 * <p><b>Complexity:</b> O(1) time, O(1) space per key.
 *
 * <p><b>Edge cases:</b> first window (no previous), gap of several windows, exactly on a boundary (fraction 0 means the full
 * previous window counts), uniform-traffic assumption makes it an approximation.
 *
 * <p><b>Brute force vs optimized:</b> the log variant is exact but O(limit) memory; this trades a small error for O(1).
 *
 * <p><b>Interview follow-ups:</b> quantify the error? How would you pick between log, counter and token bucket? Atomic update
 * in Redis (Lua script)?
 */
public class SlidingWindowCounter implements RateLimiter {
    private static final class State {
        long index;
        int current;
        int previous;
    }

    private final int limit;
    private final long windowMillis;
    private final Map<String, State> states = new HashMap<>();

    public SlidingWindowCounter(int limit, long windowMillis) {
        if (limit < 1 || windowMillis < 1) throw new IllegalArgumentException("limit>=1, window>=1");
        this.limit = limit;
        this.windowMillis = windowMillis;
    }

    @Override
    public synchronized boolean tryAcquire(String key, long nowMillis) {
        long idx = Math.floorDiv(nowMillis, windowMillis);
        State s = states.computeIfAbsent(key, k -> {
            State n = new State();
            n.index = idx;
            return n;
        });
        if (idx != s.index) {
            s.previous = idx - s.index == 1 ? s.current : 0;
            s.current = 0;
            s.index = idx;
        }
        double elapsedFraction = (double) Math.floorMod(nowMillis, windowMillis) / windowMillis;
        double estimate = s.previous * (1.0 - elapsedFraction) + s.current;
        if (estimate + 1 > limit) return false;
        s.current++;
        return true;
    }
}
