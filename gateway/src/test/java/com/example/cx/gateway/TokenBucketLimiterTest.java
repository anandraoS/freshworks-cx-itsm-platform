package com.example.cx.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketLimiterTest {
    final AtomicLong now = new AtomicLong();

    @Test
    void burstThenRefill() {
        TokenBucketLimiter l = new TokenBucketLimiter(2, 1.0, 100, now::get);
        assertThat(l.tryAcquire("k").allowed()).isTrue();
        assertThat(l.tryAcquire("k").allowed()).isTrue();
        TokenBucketLimiter.Decision d = l.tryAcquire("k");
        assertThat(d.allowed()).isFalse();
        assertThat(d.retryAfterSeconds()).isEqualTo(1);
        now.addAndGet(1_000_000_000L);
        assertThat(l.tryAcquire("k").allowed()).isTrue();
        assertThat(l.tryAcquire("k").allowed()).isFalse();
    }

    @Test
    void refillIsCappedAtCapacity() {
        TokenBucketLimiter l = new TokenBucketLimiter(2, 10.0, 100, now::get);
        l.tryAcquire("k");
        now.addAndGet(60_000_000_000L);
        assertThat(l.tryAcquire("k").allowed()).isTrue();
        assertThat(l.tryAcquire("k").allowed()).isTrue();
        assertThat(l.tryAcquire("k").allowed()).isFalse();
    }

    @Test
    void keysAreIndependentAndIdleOnesEvicted() {
        TokenBucketLimiter l = new TokenBucketLimiter(1, 1.0, 2, now::get);
        for (int i = 0; i < 5; i++) assertThat(l.tryAcquire("k" + i).allowed()).isTrue();
        now.addAndGet(10_000_000_000L);   // all buckets full again -> evictable
        l.tryAcquire("fresh");
        assertThat(l.trackedKeys()).isLessThanOrEqualTo(2);
    }
}
