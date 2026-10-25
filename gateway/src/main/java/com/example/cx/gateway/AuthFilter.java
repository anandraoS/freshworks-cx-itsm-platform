package com.example.cx.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Edge authentication: non-public routes need a Bearer token whose HS256 signature and expiry verify against the
 * shared secret. This is a cheap first line of defence only: core-service still performs full validation (revocation
 * by jti, roles, tenant checks) and remains the authority. The subject is stored for per-user rate limiting.
 */
@Component
public class AuthFilter implements WebFilter, Ordered {
    public static final String SUBJECT_ATTRIBUTE = "cx.subject";

    private final SecretKey key;
    private final ObjectMapper mapper;

    public AuthFilter(GatewayProperties props, ObjectMapper mapper) {
        String secret = props.jwt().secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("cx.security.jwt.secret must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.mapper = mapper;
    }

    /** Only proxied paths are subject to the filters; everything else is handled by local endpoints. */
    static boolean isProxied(String path) {
        return path.startsWith("/api/v1/") || path.equals("/api/v1") || path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs");
    }

    static boolean isPublic(HttpMethod method, String path) {
        if (HttpMethod.OPTIONS.equals(method)) return true;
        if (HttpMethod.POST.equals(method) && (path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/register-organization"))) return true;
        return path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs")
                || path.equals("/actuator/health") || path.startsWith("/actuator/health/");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!isProxied(path) || isPublic(exchange.getRequest().getMethod(), path)) return chain.filter(exchange);
        String header = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7) || header.length() <= 7) {
            return ErrorResponses.write(mapper, exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Missing bearer token");
        }
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(header.substring(7).trim()).getPayload();
            if (c.getSubject() == null) throw new IllegalArgumentException("no subject");
            exchange.getAttributes().put(SUBJECT_ATTRIBUTE, c.getSubject());
        } catch (Exception e) {
            return ErrorResponses.write(mapper, exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Invalid or expired token");
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 20; }
}
