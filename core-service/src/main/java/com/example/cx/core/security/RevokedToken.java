package com.example.cx.core.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Logout revocation list. Global (not tenant-filtered): a jti is unique across tenants. Rows expire with the token. */
@Entity
@Table(name = "revoked_token")
@Getter
@NoArgsConstructor
public class RevokedToken {
    @Id
    @Column(length = 64)
    private String jti;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "revoked_at", nullable = false)
    private Instant revokedAt;

    public RevokedToken(String jti, Instant expiresAt) {
        this.jti = jti;
        this.expiresAt = expiresAt;
        this.revokedAt = Instant.now();
    }
}
