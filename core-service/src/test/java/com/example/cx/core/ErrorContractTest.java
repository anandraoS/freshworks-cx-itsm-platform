package com.example.cx.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Framework-level request errors must use the contract error schema (never a 500 / framework default body). */
class ErrorContractTest extends AbstractApiTest {

    @Test
    void unsupportedMediaTypeIs415WithContractSchema() throws Exception {
        Tenant t = newTenant();
        MvcResult r = mvc.perform(auth(post("/api/v1/tickets"), t.adminToken()).contentType(MediaType.TEXT_PLAIN)
                .content("not json")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(415);
        JsonNode b = body(r);
        assertThat(b.get("status").asInt()).isEqualTo(415);
        assertThat(b.get("error").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(b.get("code").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(b.get("path").asText()).isEqualTo("/api/v1/tickets");
        assertThat(b.has("timestamp")).isTrue();
        assertThat(b.has("message")).isTrue();
    }

    @Test
    void unsupportedMethodIs405WithContractSchema() throws Exception {
        Tenant t = newTenant();
        MvcResult r = mvc.perform(auth(delete("/api/v1/tickets"), t.adminToken())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(405);
        JsonNode b = body(r);
        assertThat(b.get("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(b.get("error").asText()).isEqualTo("METHOD_NOT_ALLOWED");
    }
}
