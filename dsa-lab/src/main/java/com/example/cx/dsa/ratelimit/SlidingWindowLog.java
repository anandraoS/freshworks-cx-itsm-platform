package com.example.cx.dsa.ratelimit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Sliding window log: exact limit over any trailing window, by remembering each request timestamp.
 *
 * <p><b>Purpose / platform use case:</b> EDUCATIONAL. Use where precision matters more than memory (for example a small number of
 * sensitive operations such as password resets: "3 per rolling hour").
 *
 * <p><b>Algorithm:</b> per key keep a deque of accepted timestamps; drop those older than {@code now - window}; accept if
 * fewer than {@code limit} remain. Rejected requests are not logged (otherwise retries would extend their own lockout).
 *
 * <p><b>Complexity:</b> amortised O(1) time per request, O(limit) space per key (the log never exceeds the limit).
 *
 * <p><b>Edge cases:</b> request exactly at the window edge (older than or equal to {@code now - window} expires), out-of-order
 * timestamps, bursts at identical timestamps, very large limits make memory heavy.
 *
 * <p><b>Brute force vs optimized:</b> counting timestamps with a full scan is O(limit); the deque evicts from the front in
 * amortised O(1).
 *
 * <p><b>Interview follow-ups:</b> memory cost vs the counter variant? Redis sorted set implementation
 * ({@code ZREMRANGEBYSCORE} + {@code ZCARD})? Why not log rejected requests?
 */
public class SlidingWindowLog implements RateLimiter {
    private final int limit;
    private final long windowMillis;
    private final Map<String, Deque<Long>> logs = new HashMap<>();

    public SlidingWindowLog(int limit, long windowMillis) {
        if (limit < 1 || windowMillis < 1) throw new IllegalArgumentException("limit>=1, window>=1");
        this.limit = limit;
        this.windowMillis = windowMillis;
    }

    @Override
    public synchronized boolean tryAcquire(String key, long nowMillis) {
        Deque<Long> log = logs.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!log.isEmpty() && log.peekFirst() <= nowMillis - windowMillis) log.pollFirst();
        if (log.size() >= limit) return false;
        log.addLast(nowMillis);
        return true;
    }
}
