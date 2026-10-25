package com.example.cx.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Gateway behaviour: JWT shared secret, rate limiting and upstream timeouts. */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Upstream upstream,
        @DefaultValue Jwt jwt) {

    /** Token bucket: {@code capacity} burst, refilled at {@code refillPerSecond}. Key = user (sub) or client IP. */
    public record RateLimit(@DefaultValue("true") boolean enabled,
                            @DefaultValue("100") int capacity,
                            @DefaultValue("20") double refillPerSecond,
                            @DefaultValue("10000") int maxKeys) { }

    public record Upstream(@DefaultValue("2000") int connectTimeoutMs,
                           @DefaultValue("30000") int responseTimeoutMs) { }

    /** Same HS256 secret as core-service ({@code cx.security.jwt.secret}); dummy default for local use only. */
    public record Jwt(@DefaultValue("dev-only-NOT-A-REAL-SECRET-change-me-0123456789abcdef") String secret) { }
}
