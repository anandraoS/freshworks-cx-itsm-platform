package com.example.cx.core.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.cx.core.AbstractApiTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** AI_PROVIDER=http selected but AI_API_URL / AI_API_KEY left empty: the app boots, and analysis degrades (HTTP 200). No network is used. */
@TestPropertySource(properties = {"cx.ai.provider=http", "cx.ai.http.url=", "cx.ai.http.api-key=", "cx.ai.timeout-ms=300"})
class HttpProviderSelectedTest extends AbstractApiTest {
    @Autowired AiProvider provider;

    @Test
    void selectedButUnconfiguredHttpProviderDegradesInsteadOfFailing() throws Exception {
        assertThat(provider).isInstanceOf(HttpAiProvider.class);
        Tenant t = newTenant();
        String agent = userToken(t, "agent", "AGENT");
        String id = createTicket(agent, "Invoice problem", "LOW").get("id").asText();
        var r = doPost(agent, "/api/v1/tickets/" + id + "/ai/analyze", Map.of());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r).get("degraded").asBoolean()).isTrue();
        assertThat(body(r).get("provider").asText()).isEqualTo("HTTP");
        assertThat(body(r).get("suggestedReply").isNull()).isTrue();
    }
}
