package com.example.cx.gateway;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Accepts a well-formed inbound {@code X-Correlation-Id} or generates one; echoes it on the response. Inbound values
 * are validated (length/charset) so they cannot inject log lines or split headers.
 */
@Component
public class CorrelationIdFilter implements WebFilter, Ordered {
    public static final String HEADER = "X-Correlation-Id";
    public static final String ATTRIBUTE = "cx.correlationId";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._\\-]{1,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String in = exchange.getRequest().getHeaders().getFirst(HEADER);
        String id = in != null && SAFE.matcher(in).matches() ? in : UUID.randomUUID().toString();
        exchange.getAttributes().put(ATTRIBUTE, id);
        exchange.getResponse().getHeaders().set(HEADER, id);
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 10; }
}
