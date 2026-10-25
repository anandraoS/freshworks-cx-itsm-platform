package com.example.cx.dsa.ratelimit;

import java.util.HashMap;
import java.util.Map;

/**
 * Fixed window counter: at most {@code limit} requests per aligned window of {@code windowMillis}.
 *
 * <p><b>Purpose / platform use case:</b> EDUCATIONAL (the platform uses token buckets). Suitable for coarse quotas such as
 * "1000 API calls per tenant per hour" where boundary bursts are acceptable.
 *
 * <p><b>Algorithm:</b> counter per (key, windowIndex = now / windowMillis); reset implicitly when the index changes.
 *
 * <p><b>Complexity:</b> O(1) time, O(keys) space.
 *
 * <p><b>Edge cases:</b> boundary burst: a client can send {@code limit} requests at the end of one window and {@code limit}
 * at the start of the next (2x limit in a short span), time going backwards, stale keys never cleaned.
 *
 * <p><b>Brute force vs optimized:</b> it is already the cheapest approach; the price is the boundary-burst inaccuracy that
 * sliding window variants fix.
 *
 * <p><b>Interview follow-ups:</b> how to implement in Redis ({@code INCR} + {@code EXPIRE})? How to demonstrate the 2x burst?
 * When is the inaccuracy acceptable?
 */
public class FixedWindowCounter implements RateLimiter {
    private record Window(long index, int count) { }

    private final int limit;
    private final long windowMillis;
    private final Map<String, Window> windows = new HashMap<>();

    public FixedWindowCounter(int limit, long windowMillis) {
        if (limit < 1 || windowMillis < 1) throw new IllegalArgumentException("limit>=1, window>=1");
        this.limit = limit;
        this.windowMillis = windowMillis;
    }

    @Override
    public synchronized boolean tryAcquire(String key, long nowMillis) {
        long idx = Math.floorDiv(nowMillis, windowMillis);
        Window w = windows.get(key);
        if (w == null || w.index() != idx) w = new Window(idx, 0);
        if (w.count() >= limit) {
            windows.put(key, w);
            return false;
        }
        windows.put(key, new Window(idx, w.count() + 1));
        return true;
    }
}
