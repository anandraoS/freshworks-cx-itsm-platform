package com.example.cx.core.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {
    private CurrentUser() {}

    public static Optional<AuthUser> optional() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof AuthUser u ? Optional.of(u) : Optional.empty();
    }

    public static AuthUser require() {
        return optional().orElseThrow(() -> new IllegalStateException("No authenticated user"));
    }
}
