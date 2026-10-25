package com.example.cx.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

import com.example.cx.core.common.Redaction;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
class PlatformEndpointsTest extends AbstractApiTest {

    @Test
    void healthProbesAndPrometheusAreExposedWithoutAuth() throws Exception {
        assertThat(mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/actuator/health/readiness")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/actuator/health/liveness")).andReturn().getResponse().getStatus()).isEqualTo(200);
        Tenant t = newTenant();
        doGet(t.adminToken(), "/api/v1/ticket-categories");
        doGet(t.adminToken(), "/api/v1/ticket-categories");
        MvcResult prom = mvc.perform(get("/actuator/prometheus")).andReturn();
        assertThat(prom.getResponse().getStatus()).isEqualTo(200);
        String text = prom.getResponse().getContentAsString();
        assertThat(text).contains("cx_cache_total{").contains("result=\"hit\"").contains("result=\"miss\"");
        assertThat(text).contains("http_server_requests");
        // internal / sensitive actuator endpoints are not exposed
        assertThat(mvc.perform(get("/actuator/env")).andReturn().getResponse().getStatus()).isIn(401, 403, 404);
        assertThat(mvc.perform(get("/actuator/heapdump")).andReturn().getResponse().getStatus()).isIn(401, 403, 404);
    }

    @Test
    void openApiAndSwaggerUiArePublished() throws Exception {
        MvcResult docs = mvc.perform(get("/v3/api-docs")).andReturn();
        assertThat(docs.getResponse().getStatus()).isEqualTo(200);
        JsonNode paths = json.readTree(docs.getResponse().getContentAsString()).get("paths");
        assertThat(paths.has("/api/v1/tickets")).isTrue();
        assertThat(paths.has("/api/v1/analytics/dashboard")).isTrue();
        assertThat(paths.has("/api/v1/workflows/validate")).isTrue();
        assertThat(mvc.perform(get("/swagger-ui/index.html")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void correlationIdIsAcceptedValidatedAndEchoed() throws Exception {
        MvcResult echoed = mvc.perform(get("/actuator/health").header("X-Correlation-Id", "req-1234.abc")).andReturn();
        assertThat(echoed.getResponse().getHeader("X-Correlation-Id")).isEqualTo("req-1234.abc");
        MvcResult generated = mvc.perform(get("/actuator/health")).andReturn();
        assertThat(generated.getResponse().getHeader("X-Correlation-Id")).hasSize(36);
        // hostile values (log injection) are replaced, never echoed
        MvcResult hostile = mvc.perform(get("/actuator/health").header("X-Correlation-Id", "x\" level=ERROR msg=\"forged")).andReturn();
        assertThat(hostile.getResponse().getHeader("X-Correlation-Id")).hasSize(36).doesNotContain("forged");
        // errors carry the correlation id too
        MvcResult err = mvc.perform(get("/api/v1/tickets").header("X-Correlation-Id", "err-77")).andReturn();
        assertThat(err.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(err).get("correlationId").asText()).isEqualTo("err-77");
        assertThat(body(err).get("code").asText()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void corsAllowsOnlyTheConfiguredOrigins() throws Exception {
        // both UIs (React :5173, Angular :4200) must pass preflight, with the custom headers they send
        for (String origin : List.of("http://localhost:5173", "http://localhost:4200")) {
            MvcResult ok = mvc.perform(options("/api/v1/tickets").header("Origin", origin)
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "authorization,idempotency-key,content-type,x-correlation-id")).andReturn();
            assertThat(ok.getResponse().getStatus()).as(origin).isEqualTo(200);
            assertThat(ok.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
            assertThat(ok.getResponse().getHeader("Access-Control-Allow-Headers").toLowerCase())
                    .contains("authorization", "idempotency-key", "x-correlation-id", "content-type");
            // actual (non-preflight) response exposes the correlation id to browser JS
            MvcResult actual = mvc.perform(get("/actuator/health").header("Origin", origin)).andReturn();
            assertThat(actual.getResponse().getHeader("Access-Control-Expose-Headers")).contains("X-Correlation-Id");
        }
        MvcResult evil = mvc.perform(options("/api/v1/tickets").header("Origin", "https://evil.example.test")
                .header("Access-Control-Request-Method", "POST")).andReturn();
        assertThat(evil.getResponse().getStatus()).isEqualTo(403);
        assertThat(evil.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void unknownRoutesAndMethodsUseTheErrorSchema() throws Exception {
        Tenant t = newTenant();
        MvcResult nf = doGet(t.adminToken(), "/api/v1/does-not-exist");
        assertThat(nf.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(nf).get("code").asText()).isEqualTo("NOT_FOUND");
        MvcResult badId = doGet(t.adminToken(), "/api/v1/tickets/not-a-uuid");
        assertThat(badId.getResponse().getStatus()).isEqualTo(400);
        // internals never leak
        assertThat(nf.getResponse().getContentAsString()).doesNotContain("Exception").doesNotContain("at com.example");
    }

    @Test
    void redactionMasksSecretsInFreeText() {
        assertThat(Redaction.redact("{\"password\":\"hunter2\",\"user\":\"bob\"}")).doesNotContain("hunter2").contains("bob");
        assertThat(Redaction.redact("Authorization: Bearer abc.def.ghi")).doesNotContain("abc.def.ghi");
        assertThat(Redaction.redact("slackWebhookUrl=https://hooks.example.test/T000/B000/XXXX")).doesNotContain("XXXX");
        assertThat(Redaction.redact("apiKey: sk-live-123")).doesNotContain("sk-live-123");
        assertThat(Redaction.redact(null)).isNull();
    }
}
