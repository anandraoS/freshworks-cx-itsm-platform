package com.example.cx.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.slf4j.MDC;

/** Builds / writes the contract error schema (also from security filters, outside MVC). */
public final class ErrorWriter {
    private ErrorWriter() {}

    public static ErrorResponse build(ErrorCode code, String message, String path, List<FieldErrorDto> fields) {
        return new ErrorResponse(Instant.now(), code.status(), code.error(), code.name(), message, path,
                MDC.get(CorrelationIdFilter.MDC_KEY), fields == null || fields.isEmpty() ? null : fields);
    }

    public static void write(ObjectMapper mapper, HttpServletResponse res, ErrorCode code, String message, String path)
            throws IOException {
        res.setStatus(code.status());
        res.setContentType("application/json");
        mapper.writeValue(res.getOutputStream(), build(code, message, path, null));
    }
}
