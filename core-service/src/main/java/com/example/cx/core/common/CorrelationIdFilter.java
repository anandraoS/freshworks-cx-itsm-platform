package com.example.cx.core.common;

import com.example.cx.core.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts/returns X-Correlation-Id and puts it in the MDC. Inbound values are validated against a strict pattern so a
 * client cannot inject newlines/JSON into log lines. Also the outermost safety net that clears ThreadLocals.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Logger log = LoggerFactory.getLogger("http.access");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String in = req.getHeader(HEADER);
        String id = (in != null && SAFE.matcher(in).matches()) ? in : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, id);
        res.setHeader(HEADER, id);
        long t0 = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            // never log bodies/headers/query strings: only method, path, status, duration
            log.info("{} {} -> {} in {}ms", req.getMethod(), req.getRequestURI(), res.getStatus(),
                    (System.nanoTime() - t0) / 1_000_000);
            TenantContext.clear();
            MDC.clear();
        }
    }
}
