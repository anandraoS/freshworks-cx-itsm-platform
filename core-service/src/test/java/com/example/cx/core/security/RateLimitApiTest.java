package com.example.cx.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.cx.core.AbstractApiTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

@TestPropertySource(properties = {"cx.security.login-rate.capacity=4", "cx.security.login-rate.refill-per-minute=1"})
class RateLimitApiTest extends AbstractApiTest {

    @Test
    void loginIsRateLimitedWithRetryAfter() throws Exception {
        Tenant t = newTenant();
        int limited = 0;
        for (int i = 0; i < 8; i++) {
            MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("tenantSlug", t.slug(), "email", t.adminEmail(), "password", "wrong-" + i)))).andReturn();
            if (r.getResponse().getStatus() == 429) {
                limited++;
                assertThat(r.getResponse().getHeader("Retry-After")).isNotBlank();
                assertThat(body(r).get("code").asText()).isEqualTo("RATE_LIMITED");
            }
        }
        assertThat(limited).isGreaterThanOrEqualTo(3); // capacity 4 (+ at most ~1 refill) of 8 attempts
    }
}
