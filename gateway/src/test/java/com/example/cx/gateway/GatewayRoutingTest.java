package com.example.cx.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.cx.gateway.StubServers.Stub;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "gateway.rate-limit.capacity=10000"})
class GatewayRoutingTest {
    static Stub core;
    static Stub notif;
    static final HttpClient http = HttpClient.newHttpClient();
    static final ObjectMapper om = new ObjectMapper();

    @BeforeAll
    static void start() throws Exception {
        if (core == null) { core = new Stub("core"); notif = new Stub("notif"); }
    }

    @AfterAll
    static void stop() {
        core.stop();
        notif.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        if (core == null) { core = new Stub("core"); notif = new Stub("notif"); }
        r.add("routes.core.uri", core::url);
        r.add("routes.notifications.uri", notif::url);
    }

    @LocalServerPort int port;
    @LocalManagementPort int mgmtPort;

    String gw(String p) { return "http://127.0.0.1:" + port + p; }

    @Test
    void routesNotificationsToNotificationUpstreamAndOthersToCore() throws Exception {
        HttpResponse<String> n = StubServers.send(http, "GET", gw("/api/v1/notifications/unread-count?x=1"), StubServers.token(), null);
        assertThat(n.statusCode()).isEqualTo(200);
        assertThat(om.readTree(n.body()).get("stub").asText()).isEqualTo("notif");
        HttpResponse<String> t = StubServers.send(http, "GET", gw("/api/v1/tickets?page=0"), StubServers.token(), null);
        assertThat(om.readTree(t.body()).get("stub").asText()).isEqualTo("core");
        assertThat(core.seen).anyMatch(s -> s.uri().equals("/api/v1/tickets?page=0"));
    }

    @Test
    void forwardsBodyStatusAndHeaders() throws Exception {
        HttpResponse<String> r = StubServers.send(http, "POST", gw("/api/v1/tickets/teapot"), StubServers.token(), "{\"a\":1}", "Idempotency-Key", "k-1");
        assertThat(r.statusCode()).isEqualTo(418);
        assertThat(r.headers().firstValue("X-Stub")).contains("core");
        var seen = core.seen.stream().filter(s -> s.uri().endsWith("/teapot")).findFirst().orElseThrow();
        assertThat(seen.body()).isEqualTo("{\"a\":1}");
        assertThat(seen.headers().keySet().stream().map(String::toLowerCase)).contains("idempotency-key");
    }

    @Test
    void generatesCorrelationIdAndPropagatesProvidedOne() throws Exception {
        HttpResponse<String> gen = StubServers.send(http, "GET", gw("/api/v1/tickets"), StubServers.token(), null);
        String id = gen.headers().firstValue("X-Correlation-Id").orElseThrow();
        assertThat(id).isNotBlank();
        HttpResponse<String> given = StubServers.send(http, "GET", gw("/api/v1/tickets/abc"), StubServers.token(), null, "X-Correlation-Id", "corr-123");
        assertThat(given.headers().firstValue("X-Correlation-Id")).contains("corr-123");
        assertThat(core.seen.stream().filter(s -> s.uri().equals("/api/v1/tickets/abc")).findFirst().orElseThrow()
                .headers().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("X-Correlation-Id")).findFirst().orElseThrow().getValue())
                .containsExactly("corr-123");
    }

    @Test
    void rejectsMalformedCorrelationId() throws Exception {
        HttpResponse<String> r = StubServers.send(http, "GET", gw("/api/v1/tickets"), StubServers.token(), null, "X-Correlation-Id", "bad id;<script>");
        assertThat(r.headers().firstValue("X-Correlation-Id").orElseThrow()).doesNotContain("script");
    }

    @Test
    void missingOrInvalidTokenIs401WithContractSchema() throws Exception {
        HttpResponse<String> none = StubServers.send(http, "GET", gw("/api/v1/tickets"), null, null);
        assertThat(none.statusCode()).isEqualTo(401);
        JsonNode j = om.readTree(none.body());
        assertThat(j.get("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(j.get("status").asInt()).isEqualTo(401);
        assertThat(j.get("path").asText()).isEqualTo("/api/v1/tickets");
        assertThat(j.get("correlationId").asText()).isNotBlank();
        assertThat(j.has("timestamp")).isTrue();

        String wrong = StubServers.token("another-dummy-secret-another-dummy-secret-xx", 60_000);
        assertThat(StubServers.send(http, "GET", gw("/api/v1/tickets"), wrong, null).statusCode()).isEqualTo(401);
        String expired = StubServers.token(StubServers.SECRET, -10_000);
        assertThat(StubServers.send(http, "GET", gw("/api/v1/tickets"), expired, null).statusCode()).isEqualTo(401);
        assertThat(StubServers.send(http, "GET", gw("/api/v1/tickets"), "not.a.jwt", null).statusCode()).isEqualTo(401);
    }

    @Test
    void publicRoutesNeedNoToken() throws Exception {
        HttpResponse<String> login = StubServers.send(http, "POST", gw("/api/v1/auth/login"), null, "{}");
        assertThat(login.statusCode()).isEqualTo(200);
        HttpResponse<String> reg = StubServers.send(http, "POST", gw("/api/v1/auth/register-organization"), null, "{}");
        assertThat(reg.statusCode()).isEqualTo(200);
        assertThat(StubServers.send(http, "GET", gw("/swagger-ui.html"), null, null).statusCode()).isEqualTo(200);
        // but other auth routes are protected
        assertThat(StubServers.send(http, "GET", gw("/api/v1/auth/me"), null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void healthAndPrometheusAvailableOnManagementPort() throws Exception {
        HttpResponse<String> h = StubServers.send(http, "GET", "http://127.0.0.1:" + mgmtPort + "/actuator/health", null, null);
        assertThat(h.statusCode()).isEqualTo(200);
        assertThat(h.body()).contains("UP");
        assertThat(StubServers.send(http, "GET", "http://127.0.0.1:" + mgmtPort + "/actuator/health/readiness", null, null).statusCode()).isEqualTo(200);
        HttpResponse<String> p = StubServers.send(http, "GET", "http://127.0.0.1:" + mgmtPort + "/actuator/prometheus", null, null);
        assertThat(p.statusCode()).isEqualTo(200);
        assertThat(p.body()).contains("jvm_memory");
        // not exposed on the public port
        assertThat(StubServers.send(http, "GET", gw("/actuator/prometheus"), null, null).statusCode()).isEqualTo(404);
    }

    @Test
    void unknownPathIsJson404() throws Exception {
        HttpResponse<String> r = StubServers.send(http, "GET", gw("/nope"), null, null);
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(om.readTree(r.body()).get("code").asText()).isEqualTo("NOT_FOUND");
    }
}
