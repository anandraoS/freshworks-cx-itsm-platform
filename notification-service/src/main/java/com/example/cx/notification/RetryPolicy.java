package com.example.cx.notification;

/**
 * Pure retry/dead-letter decision logic (no I/O).
 * Attempt numbers are 1-based and count the attempt that just failed.
 */
public final class RetryPolicy {

    public enum Action { RETRY, DEAD_LETTER }

    public record Decision(Action action, long delayMillis) {
        public boolean retry() { return action == Action.RETRY; }
    }

    private final int maxAttempts;
    private final long baseDelayMs;
    private final double multiplier;
    private final long maxDelayMs;

    public RetryPolicy(int maxAttempts, long baseDelayMs, double multiplier, long maxDelayMs) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (baseDelayMs < 0 || maxDelayMs < 0) throw new IllegalArgumentException("delays must be >= 0");
        if (multiplier < 1.0) throw new IllegalArgumentException("multiplier must be >= 1");
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.multiplier = multiplier;
        this.maxDelayMs = Math.max(maxDelayMs, baseDelayMs);
    }

    public int maxAttempts() { return maxAttempts; }

    /** True when each retry has its own delay (needs per-message expiration instead of the fixed queue TTL). */
    public boolean perMessageDelay() { return multiplier > 1.0; }

    public Decision decide(int failedAttempt, Throwable cause) {
        if (cause instanceof NonRetryableException) return new Decision(Action.DEAD_LETTER, 0);
        if (failedAttempt >= maxAttempts) return new Decision(Action.DEAD_LETTER, 0);
        return new Decision(Action.RETRY, delayFor(failedAttempt));
    }

    public long delayFor(int failedAttempt) {
        double d = baseDelayMs * Math.pow(multiplier, Math.max(0, failedAttempt - 1));
        return (long) Math.min(d, maxDelayMs);
    }
}
