package com.example.cx.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Streaming reverse proxy. Routing: {@code /api/v1/notifications/**} to {@code routes.notifications.uri};
 * all other proxied paths to {@code routes.core.uri}. Connection failures map to 503, upstream timeouts to 504, both
 * in the contract error schema. Hop-by-hop headers are stripped; any client-sent X-Forwarded-For is replaced.
 */
@Component
public class ProxyFilter implements WebFilter, Ordered {
    private static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length");

    private final WebClient client;
    private final URI coreUri;
    private final URI notificationsUri;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    public ProxyFilter(RoutesProperties routes, GatewayProperties props, ObjectMapper mapper, MeterRegistry meters) {
        this.coreUri = URI.create(routes.core().uri());
        this.notificationsUri = URI.create(routes.notifications().uri());
        this.mapper = mapper;
        this.meters = meters;
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, props.upstream().connectTimeoutMs())
                .responseTimeout(Duration.ofMillis(props.upstream().responseTimeoutMs()))
                .followRedirect(false);
        this.client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(http)).build();
    }

    static boolean isNotificationsPath(String path) {
        return path.equals("/api/v1/notifications") || path.startsWith("/api/v1/notifications/");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!AuthFilter.isProxied(path)) return chain.filter(exchange);
        boolean notif = isNotificationsPath(path);
        URI base = notif ? notificationsUri : coreUri;
        String route = notif ? "notifications" : "core";
        String raw = exchange.getRequest().getURI().getRawQuery();
        URI target = URI.create(base.getScheme() + "://" + base.getAuthority() + exchange.getRequest().getURI().getRawPath()
                + (raw == null ? "" : "?" + raw));

        String correlation = exchange.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        var remote = exchange.getRequest().getRemoteAddress();
        String peer = remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();

        WebClient.RequestBodySpec spec = client.method(exchange.getRequest().getMethod()).uri(target).headers(h -> {
            exchange.getRequest().getHeaders().forEach((k, v) -> {
                if (!HOP_BY_HOP.contains(k.toLowerCase()) && !k.equalsIgnoreCase("X-Forwarded-For")
                        && !k.equalsIgnoreCase(CorrelationIdFilter.HEADER)) h.addAll(k, v);
            });
            h.set(CorrelationIdFilter.HEADER, correlation);
            h.set("X-Forwarded-For", peer);
        });
        HttpHeaders in = exchange.getRequest().getHeaders();
        boolean hasBody = in.getContentLength() > 0 || in.containsKey(HttpHeaders.TRANSFER_ENCODING);
        WebClient.RequestHeadersSpec<?> request = hasBody
                ? spec.body(BodyInserters.fromDataBuffers(exchange.getRequest().getBody())) : spec;

        return request.exchangeToMono(resp -> {
            exchange.getResponse().setStatusCode(resp.statusCode());
            resp.headers().asHttpHeaders().forEach((k, v) -> {
                if (!HOP_BY_HOP.contains(k.toLowerCase()) && !k.equalsIgnoreCase(CorrelationIdFilter.HEADER)) {
                    exchange.getResponse().getHeaders().addAll(k, v);
                }
            });
            meters.counter("gateway.proxied", "route", route, "status", Integer.toString(resp.statusCode().value())).increment();
            return exchange.getResponse().writeWith(resp.bodyToFlux(DataBuffer.class));
        }).onErrorResume(e -> {
            if (exchange.getResponse().isCommitted()) return Mono.error(e);
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            String rn = root.getClass().getSimpleName();
            boolean timeout = root instanceof TimeoutException || rn.contains("ReadTimeout") || rn.contains("ResponseTimeout");
            meters.counter("gateway.upstream.errors", "route", route, "type", timeout ? "timeout" : "unavailable").increment();
            if (timeout) {
                return ErrorResponses.write(mapper, exchange, HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT", "Upstream service timed out");
            }
            if (e instanceof WebClientRequestException) {
                return ErrorResponses.write(mapper, exchange, HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE", "Upstream service is unavailable");
            }
            return ErrorResponses.write(mapper, exchange, HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", "Bad response from upstream");
        });
    }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 40; }
}
