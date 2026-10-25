package com.example.cx.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Writes the contract error schema (docs/API-CONTRACT.md) for gateway-generated errors. */
public final class ErrorResponses {
    private ErrorResponses() { }

    public static Mono<Void> write(ObjectMapper mapper, ServerWebExchange ex, HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.name());
        body.put("code", code);
        body.put("message", message);
        body.put("path", ex.getRequest().getPath().value());
        Object cid = ex.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        body.put("correlationId", cid);
        body.put("fieldErrors", List.of());
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(body);
        } catch (Exception e) {
            bytes = "{\"code\":\"INTERNAL_ERROR\"}".getBytes(StandardCharsets.UTF_8);
        }
        if (ex.getResponse().isCommitted()) return Mono.empty();
        ex.getResponse().setStatusCode(status);
        ex.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buf = ex.getResponse().bufferFactory().wrap(bytes);
        return ex.getResponse().writeWith(Mono.just(buf));
    }
}
