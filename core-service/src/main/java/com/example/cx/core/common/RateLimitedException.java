package com.example.cx.core.common;

public class RateLimitedException extends RuntimeException {
    private final long retryAfterSeconds;

    public RateLimitedException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() { return retryAfterSeconds; }
}
