package com.example.cx.dsa.ratelimit;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RateLimiterTest {
    @Test
    void tokenBucketBurstRefillAndCap() {
        TokenBucket b = new TokenBucket(3, 1.0);
        for (int i = 0; i < 3; i++) assertTrue(b.tryAcquire("k", 0));
        assertFalse(b.tryAcquire("k", 0));
        assertFalse(b.tryAcquire("k", 500));              // 0.5 token
        assertTrue(b.tryAcquire("k", 1000));              // 1 token
        assertFalse(b.tryAcquire("k", 1000));
        // long idle caps at capacity
        for (int i = 0; i < 3; i++) assertTrue(b.tryAcquire("k", 1_000_000));
        assertFalse(b.tryAcquire("k", 1_000_000));
        // other key independent, clock going backwards is harmless
        assertTrue(b.tryAcquire("other", 5));
        assertFalse(b.tryAcquire("k", 10));
    }

    @Test
    void fixedWindowAllowsBoundaryBurstOfTwiceTheLimit() {
        FixedWindowCounter f = new FixedWindowCounter(3, 1000);
        for (int i = 0; i < 3; i++) assertTrue(f.tryAcquire("k", 900));
        assertFalse(f.tryAcquire("k", 999));
        // next window starts at 1000: 3 more pass immediately -> 6 requests within 100 ms (the known weakness)
        for (int i = 0; i < 3; i++) assertTrue(f.tryAcquire("k", 1000));
        assertFalse(f.tryAcquire("k", 1500));
    }

    @Test
    void slidingWindowLogIsExactAndDoesNotLogRejections() {
        SlidingWindowLog l = new SlidingWindowLog(3, 1000);
        assertTrue(l.tryAcquire("k", 900));
        assertTrue(l.tryAcquire("k", 950));
        assertTrue(l.tryAcquire("k", 990));
        assertFalse(l.tryAcquire("k", 1000));             // no boundary burst
        assertFalse(l.tryAcquire("k", 1899));             // rejected calls did not extend the lockout
        assertTrue(l.tryAcquire("k", 1900));              // 900 expired exactly at 1900
        assertFalse(l.tryAcquire("k", 1900));
        assertTrue(l.tryAcquire("other", 1900));
    }

    @Test
    void slidingWindowCounterApproximatesRollingWindow() {
        SlidingWindowCounter c = new SlidingWindowCounter(4, 1000);
        for (int i = 0; i < 4; i++) assertTrue(c.tryAcquire("k", 100 * i));          // window 0 full
        assertFalse(c.tryAcquire("k", 900));
        // 10% into window 1: previous weight 0.9 -> estimate 3.6, a 5th would be 4.6 > 4
        assertFalse(c.tryAcquire("k", 1100));
        // 90% into window 1: previous weight 0.1 -> estimate 0.4 -> allowed
        assertTrue(c.tryAcquire("k", 1900));
        // far later (gap of several windows): previous forgotten
        for (int i = 0; i < 4; i++) assertTrue(c.tryAcquire("k", 10_000));
        assertFalse(c.tryAcquire("k", 10_001));
    }

    @Test
    void invalidConfig() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new FixedWindowCounter(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowLog(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowCounter(1, 0));
    }
}
