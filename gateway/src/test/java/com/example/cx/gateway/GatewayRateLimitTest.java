package com.example.cx.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "gateway.rate-limit.capacity=3", "gateway.rate-limit.refill-per-second=0.01"})
class GatewayRateLimitTest {
    static StubServers.Stub core;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        core = new StubServers.Stub("core");
        r.add("routes.core.uri", core::url);
        r.add("routes.notifications.uri", core::url);
    }

    @AfterAll
    static void stop() { core.stop(); }

    @LocalServerPort int port;

    @Test
    void fourthRequestIs429WithContractSchemaAndPerUserBuckets() throws Exception {
        HttpClient c = HttpClient.newHttpClient();
        String url = "http://127.0.0.1:" + port + "/api/v1/tickets";
        String userA = StubServers.token();
        for (int i = 0; i < 3; i++) assertThat(StubServers.send(c, "GET", url, userA, null).statusCode()).isEqualTo(200);
        HttpResponse<String> limited = StubServers.send(c, "GET", url, userA, null);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        JsonNode j = new ObjectMapper().readTree(limited.body());
        assertThat(j.get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(j.get("status").asInt()).isEqualTo(429);
        // a different user has an independent bucket
        assertThat(StubServers.send(c, "GET", url, StubServers.token(), null).statusCode()).isEqualTo(200);
        // anonymous (login) is limited per client address
        String login = "http://127.0.0.1:" + port + "/api/v1/auth/login";
        for (int i = 0; i < 3; i++) assertThat(StubServers.send(c, "POST", login, null, "{}").statusCode()).isEqualTo(200);
        assertThat(StubServers.send(c, "POST", login, null, "{}").statusCode()).isEqualTo(429);
    }
}
