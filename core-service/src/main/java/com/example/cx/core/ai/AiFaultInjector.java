package com.example.cx.core.ai;

import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * TEST HOOK: forces the provider call to fail or time out so the fallback / retry / circuit-breaker paths can be tested
 * deterministically. Controlled ONLY by the property {@code cx.ai.fault-mode} (NONE | FAIL | TIMEOUT) or programmatically from tests;
 * it is not reachable over HTTP. The application refuses to start under the "docker" profile with a fault mode other than NONE.
 */
@Component
public class AiFaultInjector {
    public enum Mode { NONE, FAIL, TIMEOUT }

    private static final Logger log = LoggerFactory.getLogger(AiFaultInjector.class);
    private volatile Mode mode;
    private final AtomicInteger failFirst = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();

    public AiFaultInjector(@Value("${cx.ai.fault-mode:NONE}") String configured, Environment env) {
        this.mode = Mode.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT));
        if (mode != Mode.NONE && env.acceptsProfiles(Profiles.of("docker")))
            throw new IllegalStateException("cx.ai.fault-mode must be NONE in the docker profile");
        if (mode != Mode.NONE) log.warn("AI fault injection is ACTIVE (mode={}): suggestions will degrade on purpose", mode);
    }

    public void set(Mode m) { this.mode = m; }

    /** The next {@code n} provider calls fail (retryable), then calls succeed again. */
    public void failFirst(int n) { failFirst.set(n); }

    public void reset() { mode = Mode.NONE; failFirst.set(0); calls.set(0); }

    /** Provider invocations that reached the injection point (one per attempt). */
    public int calls() { return calls.get(); }

    /** Runs inside the provider worker thread, before the real provider. */
    void apply(long timeoutMs) {
        calls.incrementAndGet();
        if (failFirst.getAndUpdate(v -> v > 0 ? v - 1 : 0) > 0) throw new AiProviderException("injected failure", true);
        switch (mode) {
            case FAIL -> throw new AiProviderException("injected failure", true);
            case TIMEOUT -> {
                try {
                    Thread.sleep(Math.max(timeoutMs * 5, 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AiProviderException("interrupted", false, e);
                }
            }
            default -> { }
        }
    }
}
