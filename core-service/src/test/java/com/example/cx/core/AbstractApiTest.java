package com.example.cx.core;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Base for HTTP-level tests. Each test creates its OWN tenants through the public register-organization endpoint (random slug),
 * so tests are independent and also exercise the real security chain end to end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
public abstract class AbstractApiTest {
    protected static final String PASSWORD = "Test-Passw0rd!-123"; // synthetic test password

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;

    /** A freshly registered tenant plus its admin token. */
    protected record Tenant(String slug, UUID id, String adminToken, String adminEmail) {}

    protected Tenant newTenant() throws Exception {
        String slug = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String email = "admin@" + slug + ".test";
        MvcResult r = mvc.perform(post("/api/v1/auth/register-organization").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("organizationName", "Org " + slug, "slug", slug, "adminEmail", email,
                        "adminDisplayName", "Admin " + slug, "password", PASSWORD))))
                .andReturn();
        if (r.getResponse().getStatus() != 201) throw new AssertionError("register failed: " + r.getResponse().getContentAsString());
        JsonNode n = body(r);
        return new Tenant(slug, UUID.fromString(n.at("/user/tenant/id").asText()), n.get("accessToken").asText(), email);
    }

    /** Creates a user with the given roles through the admin API and returns the user's own login token. */
    protected String userToken(Tenant t, String name, String... roles) throws Exception {
        String email = name + "@" + t.slug() + ".test";
        MvcResult c = mvc.perform(post("/api/v1/users").header("Authorization", "Bearer " + t.adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "displayName", name, "roles", List.of(roles), "password", PASSWORD))))
                .andReturn();
        if (c.getResponse().getStatus() != 201) throw new AssertionError("create user failed: " + c.getResponse().getContentAsString());
        return login(t.slug(), email, PASSWORD).get("accessToken").asText();
    }

    protected UUID userId(String token) throws Exception {
        return UUID.fromString(body(mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token)).andReturn()).get("id").asText());
    }

    protected JsonNode login(String slug, String email, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("tenantSlug", slug, "email", email, "password", password)))).andReturn();
        if (r.getResponse().getStatus() != 200) throw new AssertionError("login failed: " + r.getResponse().getContentAsString());
        return body(r);
    }

    protected JsonNode body(MvcResult r) throws Exception { return json.readTree(r.getResponse().getContentAsString()); }

    protected MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", "Bearer " + token);
    }

    protected MvcResult doGet(String token, String path) throws Exception {
        return mvc.perform(auth(get(path), token)).andReturn();
    }

    protected MvcResult doPost(String token, String path, Object body) throws Exception {
        return mvc.perform(auth(post(path), token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn();
    }

    protected MvcResult doPut(String token, String path, Object body) throws Exception {
        return mvc.perform(auth(put(path), token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn();
    }

    protected static Map<String, Object> ticketBody(String title, String priority) {
        return Map.of("type", "SUPPORT", "title", title, "description", "Synthetic description for " + title,
                "priority", priority, "severity", "MINOR", "tags", List.of("test"));
    }

    /** Creates a ticket with a fresh Idempotency-Key; asserts 201 and returns the body. */
    protected JsonNode createTicket(String token, String title, String priority) throws Exception {
        MvcResult r = mvc.perform(auth(post("/api/v1/tickets"), token).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(ticketBody(title, priority)))).andReturn();
        if (r.getResponse().getStatus() != 201) throw new AssertionError("create ticket failed: " + r.getResponse().getContentAsString());
        return body(r);
    }
}
