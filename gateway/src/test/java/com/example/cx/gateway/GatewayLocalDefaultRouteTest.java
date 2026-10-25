package com.example.cx.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.cx.gateway.StubServers.Stub;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Local runtime: only {@code routes.core.uri} is set (no NOTIFICATIONS_URI), so {@code /api/v1/notifications/**} must reach the
 * core upstream (where the notification module is embedded), exactly like every other API path.
 */
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "gateway.rate-limit.capacity=10000"})
class GatewayLocalDefaultRouteTest {
    static Stub core;
    static final HttpClient http = HttpClient.newHttpClient();
    static final ObjectMapper om = new ObjectMapper();

    @AfterAll
    static void stop() { core.stop(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        if (core == null) core = new Stub("core");
        r.add("routes.core.uri", core::url);   // routes.notifications.uri deliberately NOT set: defaults to the core uri
    }

    @LocalServerPort int port;

    @Test
    void notificationsFallBackToTheCoreUpstreamWhenNoSeparateServiceIsConfigured() throws Exception {
        for (String path : new String[] {"/api/v1/notifications?page=0&size=20", "/api/v1/notifications/unread-count"}) {
            HttpResponse<String> n = StubServers.send(http, "GET", "http://127.0.0.1:" + port + path, StubServers.token(), null);
            assertThat(n.statusCode()).isEqualTo(200);
            assertThat(om.readTree(n.body()).get("stub").asText()).isEqualTo("core");
        }
        assertThat(core.seen).anyMatch(s -> s.uri().equals("/api/v1/notifications/unread-count"));
    }
}
