package com.example.cx.core.ai;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Circuit-breaker-lite (per node, per provider, not per tenant: it tracks provider health). CLOSED -> after N consecutive failed
 * analyses -> OPEN for a cool-down (requests fail fast, no provider call) -> HALF-OPEN: ONE trial request is let through; success
 * closes it, failure re-opens it. Deliberately tiny: no sliding windows, no metrics beyond what the service counts.
 */
@Component
public class AiCircuitBreaker {
    private final AiSettings settings;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntil = new AtomicLong();
    private final AtomicLong trialToken = new AtomicLong();

    public AiCircuitBreaker(AiSettings settings) { this.settings = settings; }

    /** true = the call may proceed. */
    public boolean allow() {
        long until = openUntil.get();
        if (until == 0) return true;
        long now = System.currentTimeMillis();
        if (now < until) return false;
        // half-open: exactly one caller wins the trial slot
        return trialToken.compareAndSet(0, now);
    }

    public void success() {
        consecutiveFailures.set(0);
        openUntil.set(0);
        trialToken.set(0);
    }

    public void failure() {
        int n = consecutiveFailures.incrementAndGet();
        if (n >= settings.breakerFailures || trialToken.get() != 0) {
            openUntil.set(System.currentTimeMillis() + settings.breakerOpenMs);
            trialToken.set(0);
        }
    }

    public boolean isOpen() { return openUntil.get() > System.currentTimeMillis(); }

    public void reset() { success(); }
}
