package com.example.cx.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code routes.core.uri} and {@code routes.notifications.uri}. Local default: notifications are embedded in core. */
@ConfigurationProperties(prefix = "routes")
public record RoutesProperties(Route core, Route notifications) {
    public record Route(String uri) { }
}
