package com.example.cx.core.security;

import com.example.cx.core.tenancy.TenantContext;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stateless bearer-token filter. The ONLY place (besides login/registration/system jobs) that binds the TenantContext,
 * and it does so exclusively from the verified token's "tid" claim. Client-supplied tenant ids are never read.
 */
public class JwtAuthFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private final JwtService jwt;
    private final TokenRevocationService revocations;

    public JwtAuthFilter(JwtService jwt, TokenRevocationService revocations) {
        this.jwt = jwt;
        this.revocations = revocations;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        try {
            if (h != null && h.regionMatches(true, 0, "Bearer ", 0, 7)) {
                try {
                    AuthUser u = jwt.verify(h.substring(7).trim());
                    if (!revocations.isRevoked(u.jti())) {
                        var auth = new UsernamePasswordAuthenticationToken(u, null,
                                u.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r.name())).toList());
                        SecurityContextHolder.getContext().setAuthentication(auth);
                        TenantContext.set(u.tenantId());
                        MDC.put("userId", u.userId().toString());
                    }
                } catch (JwtException | IllegalArgumentException e) {
                    log.debug("Rejected bearer token: {}", e.getClass().getSimpleName());
                }
            }
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
            MDC.remove("userId");
            SecurityContextHolder.clearContext();
        }
    }
}
