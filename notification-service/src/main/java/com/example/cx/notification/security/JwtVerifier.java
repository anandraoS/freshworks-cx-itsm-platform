package com.example.cx.notification.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;

/**
 * Validates the platform's HS256 access token (same shared secret as core-service: the secret string's UTF-8 bytes
 * are the HMAC key). Claims: sub=userId, tid=tenantId, roles=[...], email, jti. Tenant ALWAYS comes from {@code tid}.
 */
public class JwtVerifier {
    private final SecretKey key;

    public JwtVerifier(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("cx.security.jwt.secret must be at least 32 bytes for HS256");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws JwtException or IllegalArgumentException when the token is invalid, expired, wrong alg or lacks claims */
    public AuthPrincipal verify(String token) {
        Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        if (!"HS256".equals(jws.getHeader().getAlgorithm())) throw new JwtException("unsupported algorithm");
        Claims c = jws.getPayload();
        String sub = c.getSubject();
        String tid = c.get("tid", String.class);
        if (sub == null || tid == null) throw new JwtException("missing sub/tid claim");
        List<String> roles = c.get("roles") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        return new AuthPrincipal(UUID.fromString(sub), UUID.fromString(tid), roles, c.get("email", String.class), c.getId());
    }
}
