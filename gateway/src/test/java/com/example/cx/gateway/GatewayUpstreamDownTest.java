package com.example.cx.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class GatewayUpstreamDownTest {
    static int freePort;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        try (ServerSocket s = new ServerSocket(0)) { freePort = s.getLocalPort(); }   // closed again: nothing listens
        r.add("routes.core.uri", () -> "http://127.0.0.1:" + freePort);
        r.add("routes.notifications.uri", () -> "http://127.0.0.1:" + freePort);
    }

    @LocalServerPort int port;

    @Test
    void upstreamDownYields503InContractSchema() throws Exception {
        HttpResponse<String> r = StubServers.send(HttpClient.newHttpClient(), "GET", "http://127.0.0.1:" + port + "/api/v1/tickets",
                StubServers.token(), null, "X-Correlation-Id", "down-1");
        assertThat(r.statusCode()).isEqualTo(503);
        JsonNode j = new ObjectMapper().readTree(r.body());
        assertThat(j.get("code").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(j.get("status").asInt()).isEqualTo(503);
        assertThat(j.get("correlationId").asText()).isEqualTo("down-1");
        assertThat(r.headers().firstValue("Content-Type").orElse("")).contains("application/json");
    }
}
