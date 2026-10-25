package com.example.cx.core.security;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Authenticated principal built ONLY from a verified JWT. */
public record AuthUser(UUID userId, UUID tenantId, String email, Set<Role> roles, String jti, Instant expiresAt) {
    public boolean has(Role r) { return roles.contains(r); }
    /** Staff = anyone above CUSTOMER. A user holding only CUSTOMER sees only their own tickets. */
    public boolean isStaff() { return roles.stream().anyMatch(Role::isStaff); }
    public boolean isManagerOrAbove() { return has(Role.MANAGER) || has(Role.ADMIN); }
    public boolean isAdmin() { return has(Role.ADMIN); }
}
