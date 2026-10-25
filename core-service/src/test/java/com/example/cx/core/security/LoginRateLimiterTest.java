package com.example.cx.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.cx.core.common.RateLimitedException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LoginRateLimiterTest {

    @Test
    void allowsBurstThenBlocksThenRefills() {
        AtomicLong clock = new AtomicLong();
        LoginRateLimiter l = new LoginRateLimiter(3, 60, 100, clock::get); // 60/min = 1 per second
        for (int i = 0; i < 3; i++) l.check("k");
        assertThatThrownBy(() -> l.check("k")).isInstanceOf(RateLimitedException.class)
                .satisfies(e -> assertThat(((RateLimitedException) e).retryAfterSeconds()).isBetween(1L, 2L));
        clock.addAndGet(2_000_000_000L); // 2 seconds later -> 2 tokens
        l.check("k");
        l.check("k");
        assertThatThrownBy(() -> l.check("k")).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void keysAreIndependent() {
        LoginRateLimiter l = new LoginRateLimiter(1, 1, 100, () -> 0L);
        l.check("a");
        l.check("b");
        assertThatThrownBy(() -> l.check("a")).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void trackedKeysAreBounded() {
        LoginRateLimiter l = new LoginRateLimiter(5, 5, 50, () -> 0L);
        for (int i = 0; i < 5_000; i++) l.check("attacker-key-" + i);
        assertThat(l.trackedKeys()).isLessThanOrEqualTo(60); // maximumSize 50 (+ eviction slack)
    }
}
