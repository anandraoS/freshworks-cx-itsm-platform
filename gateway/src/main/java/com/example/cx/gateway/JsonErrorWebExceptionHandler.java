package com.example.cx.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Any exception that escapes (404 for unknown paths, 405, unexpected) is rendered in the contract error schema. */
@Component
@Order(-2)
public class JsonErrorWebExceptionHandler implements ErrorWebExceptionHandler {
    private final ObjectMapper mapper;

    public JsonErrorWebExceptionHandler(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String message = "Unexpected gateway error";
        if (ex instanceof ResponseStatusException rse) {
            HttpStatus resolved = HttpStatus.resolve(rse.getStatusCode().value());
            if (resolved != null) status = resolved;
            if (!status.is5xxServerError()) message = rse.getReason() != null ? rse.getReason() : status.getReasonPhrase();
        }
        String code = status == HttpStatus.NOT_FOUND ? "NOT_FOUND" : status == HttpStatus.INTERNAL_SERVER_ERROR ? "INTERNAL_ERROR" : status.name();
        return ErrorResponses.write(mapper, exchange, status, code, message);
    }
}
