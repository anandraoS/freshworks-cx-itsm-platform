package com.example.cx.core.security;

import com.example.cx.core.config.CxProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/** Issues and verifies HS256 JWTs. Claims: sub=userId, tid=tenantId, roles, email, jti (+ iss/iat/exp). */
@Service
public class JwtService {
    private final SecretKey key;
    private final String issuer;
    private final Duration ttl;

    public JwtService(CxProperties props) {
        byte[] secret = props.security().jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) throw new IllegalStateException("cx.security.jwt.secret must be at least 32 bytes");
        this.key = Keys.hmacShaKeyFor(secret);
        this.issuer = props.security().jwt().issuer();
        this.ttl = Duration.ofMinutes(props.security().jwt().ttlMinutes());
    }

    public record Issued(String token, String jti, Instant expiresAt) {}

    public Issued issue(UUID userId, UUID tenantId, String email, Collection<Role> roles) {
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .id(jti).issuer(issuer).subject(userId.toString())
                .claim("tid", tenantId.toString())
                .claim("email", email)
                .claim("roles", roles.stream().map(Enum::name).sorted().toList())
                .issuedAt(Date.from(now)).expiration(Date.from(exp))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new Issued(token, jti, exp);
    }

    public long ttlSeconds() { return ttl.toSeconds(); }

    /** @throws JwtException on bad signature, expiry, wrong issuer or malformed claims */
    public AuthUser verify(String token) {
        Claims c = Jwts.parser().verifyWith(key).requireIssuer(issuer).clockSkewSeconds(5)
                .build().parseSignedClaims(token).getPayload();
        try {
            Set<Role> roles = EnumSet.noneOf(Role.class);
            Object raw = c.get("roles");
            if (raw instanceof List<?> l) for (Object o : l) roles.add(Role.valueOf(String.valueOf(o)));
            if (roles.isEmpty() || c.getId() == null) throw new JwtException("missing roles/jti");
            return new AuthUser(UUID.fromString(c.getSubject()), UUID.fromString(c.get("tid", String.class)),
                    c.get("email", String.class), roles, c.getId(), c.getExpiration().toInstant());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new JwtException("malformed claims");
        }
    }
}
