package com.example.cx.notification.security;

import com.example.cx.notification.api.ErrorWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Authenticates /api/v1/notifications/** calls itself (independent of any Spring Security setup in the host app):
 * correlation id, Bearer JWT validation, principal exposed as request attribute {@link #PRINCIPAL}.
 */
public class JwtAuthInterceptor implements HandlerInterceptor {
    public static final String PRINCIPAL = "cx.notifications.principal";
    private static final Pattern SAFE_CID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final JwtVerifier verifier;

    public JwtAuthInterceptor(JwtVerifier verifier) { this.verifier = verifier; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        String cid = req.getHeader(ErrorWriter.CORRELATION_HEADER);
        if (cid == null || !SAFE_CID.matcher(cid).matches()) cid = UUID.randomUUID().toString();
        req.setAttribute(ErrorWriter.CORRELATION_HEADER, cid);
        res.setHeader(ErrorWriter.CORRELATION_HEADER, cid);

        if ("OPTIONS".equals(req.getMethod())) return true;
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            ErrorWriter.write(res, req, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Missing bearer token");
            return false;
        }
        try {
            req.setAttribute(PRINCIPAL, verifier.verify(auth.substring(7).trim()));
            return true;
        } catch (Exception e) {   // JwtException, IllegalArgumentException (bad uuid claims)
            ErrorWriter.write(res, req, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Invalid or expired token");
            return false;
        }
    }
}
