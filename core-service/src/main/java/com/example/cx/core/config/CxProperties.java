package com.example.cx.core.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Typed configuration. Every value has a safe default; secrets come from env (see application-docker.yml). */
@ConfigurationProperties(prefix = "cx")
public record CxProperties(
        @DefaultValue Security security,
        @DefaultValue Cors cors,
        @DefaultValue Seed seed,
        @DefaultValue Outbox outbox,
        @DefaultValue Sla sla,
        @DefaultValue Cache cache) {

    public static final String DEV_JWT_SECRET = "dev-only-NOT-A-REAL-SECRET-change-me-0123456789abcdef";

    public record Security(@DefaultValue Jwt jwt, @DefaultValue LoginRate loginRate,
                           @DefaultValue("false") boolean trustForwardedFor,
                           @DefaultValue("3") int revocationNegativeCacheSeconds) {}

    /** HS256 secret must be >= 32 bytes. The default is a clearly dummy dev value; the docker profile refuses it. */
    public record Jwt(@DefaultValue(DEV_JWT_SECRET) String secret,
                      @DefaultValue("60") long ttlMinutes,
                      @DefaultValue("cx-core-service") String issuer) {}

    /** Token bucket: `capacity` burst, refilled at `refillPerMinute`. */
    public record LoginRate(@DefaultValue("10") int capacity, @DefaultValue("5") int refillPerMinute,
                            @DefaultValue("10000") int maxTrackedKeys) {}

    public record Cors(@DefaultValue("http://localhost:5173") List<String> allowedOrigins) {}

    public record Seed(@DefaultValue("false") boolean enabled) {}

    public record Outbox(@DefaultValue("50") int batchSize, @DefaultValue("8") int maxAttempts,
                         @DefaultValue("1000") long pollMs, @DefaultValue("60") int lockSeconds,
                         @DefaultValue("2") int backoffBaseSeconds, @DefaultValue("true") boolean relayEnabled,
                         @DefaultValue("true") boolean purgeEnabled, @DefaultValue("7") int retentionDays,
                         @DefaultValue("500") int purgeBatchSize, @DefaultValue("20") int purgeMaxBatchesPerRun,
                         @DefaultValue("3600000") long purgeIntervalMs) {}

    public record Sla(@DefaultValue("true") boolean jobEnabled, @DefaultValue("60000") long jobIntervalMs,
                      @DefaultValue("200") int batchPerTenant, @DefaultValue("75") int atRiskPercent) {}

    public record Cache(@DefaultValue("10000") int maxEntries, @DefaultValue("5") int redisFailureBackoffSeconds) {}
}
