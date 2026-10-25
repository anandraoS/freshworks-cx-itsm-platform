package com.example.cx.notification.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/** Builds and writes contract-shaped errors (used by the JWT interceptor, which runs before MVC exception handling). */
public final class ErrorWriter {
    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ErrorWriter() {}

    public static ApiError build(HttpStatus status, String code, String message, HttpServletRequest req, List<ApiError.FieldError> fe) {
        String cid = (String) req.getAttribute(CORRELATION_HEADER);
        return new ApiError(Instant.now().toString(), status.value(), status.name(), code, message, req.getRequestURI(), cid, fe);
    }

    public static void write(HttpServletResponse res, HttpServletRequest req, HttpStatus status, String code, String message) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        MAPPER.writeValue(res.getOutputStream(), build(status, code, message, req, List.of()));
    }
}
