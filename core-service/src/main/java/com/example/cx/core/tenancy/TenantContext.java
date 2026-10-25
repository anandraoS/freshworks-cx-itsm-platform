package com.example.cx.core.tenancy;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * Holds the tenant of the current unit of work. SECURITY RULE: the value is set ONLY from
 * (a) a signature-verified JWT (JwtAuthFilter), (b) server-side tenant resolution during login/registration, or
 * (c) system jobs that iterate tenants themselves. Never from a request parameter, path or header.
 * An architecture test enforces which classes may call the mutators.
 */
public final class TenantContext {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static Optional<UUID> current() { return Optional.ofNullable(CURRENT.get()); }

    public static UUID require() {
        UUID t = CURRENT.get();
        if (t == null) throw new IllegalStateException("No tenant bound to the current thread");
        return t;
    }

    public static void set(UUID tenantId) {
        CURRENT.set(tenantId);
        MDC.put("tenantId", tenantId.toString());
    }

    public static void clear() {
        CURRENT.remove();
        MDC.remove("tenantId");
    }

    /** Runs with the tenant bound, restoring the previous binding afterwards. */
    public static <T> T callAs(UUID tenantId, Supplier<T> body) {
        UUID prev = CURRENT.get();
        set(tenantId);
        try {
            return body.get();
        } finally {
            if (prev == null) clear(); else set(prev);
        }
    }

    public static void runAs(UUID tenantId, Runnable body) {
        callAs(tenantId, () -> { body.run(); return null; });
    }
}
