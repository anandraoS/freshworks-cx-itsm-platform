package com.example.cx.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.cx.notification.RetryPolicy.Action;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void retriesUntilMaxAttemptsThenDeadLetters() {
        RetryPolicy p = new RetryPolicy(3, 5000, 1.0, 60000);
        assertThat(p.decide(1, new RuntimeException()).action()).isEqualTo(Action.RETRY);
        assertThat(p.decide(2, new RuntimeException()).action()).isEqualTo(Action.RETRY);
        assertThat(p.decide(3, new RuntimeException()).action()).isEqualTo(Action.DEAD_LETTER);
        assertThat(p.decide(4, new RuntimeException()).action()).isEqualTo(Action.DEAD_LETTER);
    }

    @Test
    void fixedDelayWhenMultiplierIsOne() {
        RetryPolicy p = new RetryPolicy(5, 2000, 1.0, 60000);
        assertThat(p.perMessageDelay()).isFalse();
        assertThat(p.decide(1, new RuntimeException()).delayMillis()).isEqualTo(2000);
        assertThat(p.decide(4, new RuntimeException()).delayMillis()).isEqualTo(2000);
    }

    @Test
    void exponentialBackoffIsCapped() {
        RetryPolicy p = new RetryPolicy(10, 1000, 2.0, 5000);
        assertThat(p.perMessageDelay()).isTrue();
        assertThat(p.delayFor(1)).isEqualTo(1000);
        assertThat(p.delayFor(2)).isEqualTo(2000);
        assertThat(p.delayFor(3)).isEqualTo(4000);
        assertThat(p.delayFor(4)).isEqualTo(5000);
        assertThat(p.delayFor(9)).isEqualTo(5000);
    }

    @Test
    void nonRetryableGoesStraightToDeadLetter() {
        RetryPolicy p = new RetryPolicy(5, 1000, 1.0, 1000);
        assertThat(p.decide(1, new NonRetryableException("poison")).action()).isEqualTo(Action.DEAD_LETTER);
    }

    @Test
    void singleAttemptPolicyNeverRetries() {
        assertThat(new RetryPolicy(1, 1000, 1.0, 1000).decide(1, new RuntimeException()).retry()).isFalse();
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new RetryPolicy(0, 1, 1.0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(1, -1, 1.0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(1, 1, 0.5, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
