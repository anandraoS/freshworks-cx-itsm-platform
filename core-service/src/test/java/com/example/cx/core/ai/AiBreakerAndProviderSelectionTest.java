package com.example.cx.core.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.cx.core.AbstractApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Circuit-breaker behaviour through the real endpoint (2 failures open it; no retries so each analysis = 1 provider call). */
@TestPropertySource(properties = {"cx.ai.timeout-ms=300", "cx.ai.max-retries=0", "cx.ai.breaker-failures=2", "cx.ai.breaker-open-ms=60000",
        "cx.ai.rate-limit-per-minute=100"})
class AiBreakerAndProviderSelectionTest extends AbstractApiTest {
    @Autowired AiFaultInjector faults;
    @Autowired AiCircuitBreaker breaker;
    @Autowired AiProvider provider;

    @AfterEach
    void reset() {
        faults.reset();
        breaker.reset();
    }

    @Test
    void defaultProviderIsTheMockRuleBasedOne() {
        assertThat(provider).isInstanceOf(MockRuleBasedAiProvider.class);
        assertThat(provider.name()).isEqualTo("MOCK_RULE_BASED");
    }

    @Test
    void breakerOpensAfterRepeatedFailuresAndFailsFastWithoutCallingTheProvider() throws Exception {
        Tenant t = newTenant();
        String agent = userToken(t, "agent", "AGENT");
        String customer = userToken(t, "cust", "CUSTOMER");
        String id = createTicket(customer, "Invoice problem", "LOW").get("id").asText();

        faults.set(AiFaultInjector.Mode.FAIL);
        for (int i = 0; i < 2; i++) assertThat(analyze(agent, id).get("degraded").asBoolean()).isTrue();
        assertThat(faults.calls()).isEqualTo(2);
        assertThat(breaker.isOpen()).isTrue();

        assertThat(analyze(agent, id).get("degraded").asBoolean()).isTrue();
        assertThat(faults.calls()).as("open circuit: provider not called").isEqualTo(2);

        faults.reset();
        breaker.reset();
        assertThat(analyze(agent, id).get("degraded").asBoolean()).isFalse();
    }

    private JsonNode analyze(String token, String id) throws Exception {
        var r = doPost(token, "/api/v1/tickets/" + id + "/ai/analyze", Map.of());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return body(r);
    }

    @Test
    void httpProviderIsOptionalAndFailsClosedWhenNotConfigured() {
        HttpAiProvider unconfigured = new HttpAiProvider("", "", 300, new ObjectMapper());
        assertThat(unconfigured.name()).isEqualTo("HTTP");
        var req = new AiProvider.AiRequest("t", "d", null, List.of(), List.of());
        assertThatThrownBy(() -> unconfigured.analyze(req)).isInstanceOf(AiProviderException.class)
                .satisfies(e -> assertThat(((AiProviderException) e).retryable()).isFalse());
        // plain http is rejected as well (https only); no network call is ever made
        HttpAiProvider insecure = new HttpAiProvider("http://example.invalid/analyze", "", 300, new ObjectMapper());
        assertThatThrownBy(() -> insecure.analyze(req)).isInstanceOf(AiProviderException.class);
    }
}
