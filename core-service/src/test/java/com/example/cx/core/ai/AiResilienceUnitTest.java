package com.example.cx.core.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.cx.core.common.RateLimitedException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiResilienceUnitTest {
    private static AiSettings settings(int breakerFailures, long openMs, int rate) {
        return new AiSettings(300, 2, 5000, rate, breakerFailures, openMs);
    }

    @Test
    void settingsAreClampedToHardBounds() {
        AiSettings s = new AiSettings(1, 99, 999_999, 0, 0, 1);
        assertThat(s.maxRetries).isEqualTo(3);
        assertThat(s.timeoutMs).isGreaterThanOrEqualTo(50);
        assertThat(s.totalDeadlineMs).isLessThanOrEqualTo(60_000);
        assertThat(s.ratePerMinute).isEqualTo(1);
        assertThat(s.breakerFailures).isEqualTo(1);
    }

    @Test
    void breakerOpensAfterConsecutiveFailuresAndClosesOnSuccess() {
        AiCircuitBreaker b = new AiCircuitBreaker(settings(3, 60_000, 10));
        assertThat(b.allow()).isTrue();
        b.failure();
        b.failure();
        assertThat(b.isOpen()).isFalse();
        b.failure();
        assertThat(b.isOpen()).isTrue();
        assertThat(b.allow()).as("open => fail fast").isFalse();
        b.reset();
        assertThat(b.allow()).isTrue();
    }

    @Test
    void breakerHalfOpenLetsExactlyOneTrialThrough() throws Exception {
        AiCircuitBreaker b = new AiCircuitBreaker(settings(1, 100, 10));
        b.failure();
        assertThat(b.allow()).isFalse();
        Thread.sleep(150);
        assertThat(b.allow()).as("first caller after the cool-down is the trial").isTrue();
        assertThat(b.allow()).as("second caller is still rejected").isFalse();
        b.failure();                               // trial failed -> open again
        assertThat(b.isOpen()).isTrue();
        Thread.sleep(150);
        assertThat(b.allow()).isTrue();
        b.success();                               // trial succeeded -> closed
        assertThat(b.allow()).isTrue();
        assertThat(b.allow()).isTrue();
    }

    @Test
    void rateLimiterIsPerTenant() {
        AiRateLimiter l = new AiRateLimiter(settings(5, 1000, 3));
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        for (int i = 0; i < 3; i++) l.check(a);
        assertThatThrownBy(() -> l.check(a)).isInstanceOf(RateLimitedException.class);
        l.check(b);                                // another tenant is unaffected
    }

    @Test
    void faultInjectionIsRefusedInTheDockerProfile() {
        org.springframework.mock.env.MockEnvironment docker = new org.springframework.mock.env.MockEnvironment();
        docker.setActiveProfiles("docker");
        assertThatThrownBy(() -> new AiFaultInjector("FAIL", docker)).isInstanceOf(IllegalStateException.class);
        assertThat(new AiFaultInjector("NONE", docker)).isNotNull();
        assertThat(new AiFaultInjector("FAIL", new org.springframework.mock.env.MockEnvironment())).isNotNull();
    }
}
