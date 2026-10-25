package com.example.cx.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetSocketAddress;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Token-bucket rate limit keyed by authenticated subject, or by socket peer address for anonymous routes (login).
 * {@code X-Forwarded-For} is deliberately NOT trusted: a client could rotate it to evade the limit.
 */
@Component
public class RateLimitFilter implements WebFilter, Ordered {
    private final TokenBucketLimiter limiter;
    private final boolean enabled;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    public RateLimitFilter(GatewayProperties props, ObjectMapper mapper, MeterRegistry meters) {
        GatewayProperties.RateLimit rl = props.rateLimit();
        this.enabled = rl.enabled();
        this.limiter = new TokenBucketLimiter(rl.capacity(), rl.refillPerSecond(), rl.maxKeys(), System::nanoTime);
        this.mapper = mapper;
        this.meters = meters;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled || !AuthFilter.isProxied(exchange.getRequest().getPath().value())) return chain.filter(exchange);
        String subject = exchange.getAttribute(AuthFilter.SUBJECT_ATTRIBUTE);
        String key;
        if (subject != null) {
            key = "u:" + subject;
        } else {
            InetSocketAddress addr = exchange.getRequest().getRemoteAddress();
            key = "ip:" + (addr == null || addr.getAddress() == null ? "unknown" : addr.getAddress().getHostAddress());
        }
        TokenBucketLimiter.Decision d = limiter.tryAcquire(key);
        if (d.allowed()) return chain.filter(exchange);
        meters.counter("gateway.rate.limited").increment();
        exchange.getResponse().getHeaders().set("Retry-After", Long.toString(d.retryAfterSeconds()));
        return ErrorResponses.write(mapper, exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests; retry later");
    }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 30; }
}
