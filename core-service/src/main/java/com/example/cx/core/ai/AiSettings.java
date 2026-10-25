package com.example.cx.core.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Resilience knobs (all bounded). Properties: cx.ai.timeout-ms, max-retries, total-deadline-ms, rate-limit-per-minute, breaker-*. */
@Component
public class AiSettings {
    final long timeoutMs;
    final int maxRetries;
    final long totalDeadlineMs;
    final int ratePerMinute;
    final int breakerFailures;
    final long breakerOpenMs;

    public AiSettings(@Value("${cx.ai.timeout-ms:3000}") long timeoutMs,
                      @Value("${cx.ai.max-retries:2}") int maxRetries,
                      @Value("${cx.ai.total-deadline-ms:10000}") long totalDeadlineMs,
                      @Value("${cx.ai.rate-limit-per-minute:30}") int ratePerMinute,
                      @Value("${cx.ai.breaker-failures:5}") int breakerFailures,
                      @Value("${cx.ai.breaker-open-ms:30000}") long breakerOpenMs) {
        this.timeoutMs = Math.max(50, Math.min(timeoutMs, 30_000));
        this.maxRetries = Math.max(0, Math.min(maxRetries, 3));          // hard upper bound: 1 call + 3 retries
        this.totalDeadlineMs = Math.max(this.timeoutMs, Math.min(totalDeadlineMs, 60_000));
        this.ratePerMinute = Math.max(1, Math.min(ratePerMinute, 10_000));
        this.breakerFailures = Math.max(1, Math.min(breakerFailures, 100));
        this.breakerOpenMs = Math.max(100, Math.min(breakerOpenMs, 600_000));
    }
}
