package com.example.cx.notification;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.cx.events.DomainEvent;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(classes = NotificationServiceApplication.class,
        properties = "cx.security.jwt.secret=test-only-dummy-secret-0123456789abcdefXYZ")
@AutoConfigureMockMvc
class NotificationApiTest {
    private static final String SECRET = "test-only-dummy-secret-0123456789abcdefXYZ";

    @Autowired MockMvc mvc;
    @Autowired NotificationProcessor processor;

    private static String token(UUID user, UUID tenant, String secret, Date exp) {
        return Jwts.builder().subject(user.toString()).claim("tid", tenant.toString()).claim("roles", List.of("AGENT"))
                .claim("email", "a@x.test").id(UUID.randomUUID().toString()).expiration(exp)
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private static String bearer(UUID user, UUID tenant) {
        return "Bearer " + token(user, tenant, SECRET, Date.from(Instant.now().plusSeconds(600)));
    }

    private DomainEvent seed(UUID tenant, UUID user, String type) {
        DomainEvent e = TestSupport.event(tenant, type, user);
        processor.process(e);
        return e;
    }

    private ResultActions list(String auth, String query) throws Exception {
        return mvc.perform(get("/api/v1/notifications" + query).header("Authorization", auth));
    }

    @Test
    void missingOrInvalidTokenIs401WithContractErrorShapeAndCorrelationId() throws Exception {
        mvc.perform(get("/api/v1/notifications").header("X-Correlation-Id", "abc-123"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Correlation-Id", "abc-123"))
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")))
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.path", is("/api/v1/notifications")))
                .andExpect(jsonPath("$.correlationId", is("abc-123")))
                .andExpect(jsonPath("$.timestamp", notNullValue()));

        UUID u = UUID.randomUUID(), t = UUID.randomUUID();
        list("Bearer " + token(u, t, "another-dummy-secret-0123456789abcdefXYZ-other", Date.from(Instant.now().plusSeconds(60))), "")
                .andExpect(status().isUnauthorized());
        list("Bearer " + token(u, t, SECRET, Date.from(Instant.now().minusSeconds(60))), "").andExpect(status().isUnauthorized());
        list("Bearer garbage", "").andExpect(status().isUnauthorized());
        list("Basic abc", "").andExpect(status().isUnauthorized());
    }

    @Test
    void unsafeCorrelationIdIsReplaced() throws Exception {
        mvc.perform(get("/api/v1/notifications").header("X-Correlation-Id", "bad id\twith space"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.correlationId", notNullValue()))
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void usersSeeOnlyTheirOwnNotificationsWithinTheirTenant() throws Exception {
        UUID tenantA = UUID.randomUUID(), tenantB = UUID.randomUUID();
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        seed(tenantA, alice, "ticket.created");
        seed(tenantA, alice, "ticket.assigned");
        seed(tenantA, bob, "ticket.created");
        seed(tenantB, alice, "ticket.created");   // SAME user id, different tenant

        list(bearer(alice, tenantA), "").andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements", is(2)))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(20)))
                .andExpect(jsonPath("$.content[0].read", is(false)))
                .andExpect(jsonPath("$.content[0].ticketId", notNullValue()));
        list(bearer(bob, tenantA), "").andExpect(jsonPath("$.content", hasSize(1)));
        list(bearer(alice, tenantB), "").andExpect(jsonPath("$.content", hasSize(1)));
        list(bearer(alice, UUID.randomUUID()), "").andExpect(jsonPath("$.content", hasSize(0)));
        mvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", bearer(alice, tenantA)))
                .andExpect(jsonPath("$.count", is(2)));
    }

    @Test
    void tenantIdQueryParameterCannotSwitchTenant() throws Exception {
        UUID tenantA = UUID.randomUUID(), tenantB = UUID.randomUUID(), alice = UUID.randomUUID();
        seed(tenantB, alice, "ticket.created");
        list(bearer(alice, tenantA), "?tenantId=" + tenantB + "&userId=" + alice).andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void markReadIsOwnOnlyAndCrossTenantIdsLookNotFound() throws Exception {
        UUID tenantA = UUID.randomUUID(), tenantB = UUID.randomUUID();
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        seed(tenantA, alice, "ticket.created");
        String id = com.jayway.jsonpath.JsonPath.read(
                list(bearer(alice, tenantA), "").andReturn().getResponse().getContentAsString(), "$.content[0].id");

        mvc.perform(post("/api/v1/notifications/" + id + "/read").header("Authorization", bearer(bob, tenantA)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code", is("NOT_FOUND")));
        mvc.perform(post("/api/v1/notifications/" + id + "/read").header("Authorization", bearer(alice, tenantB)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/notifications/" + UUID.randomUUID() + "/read").header("Authorization", bearer(alice, tenantA)))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/notifications/" + id + "/read").header("Authorization", bearer(alice, tenantA)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/notifications/" + id + "/read").header("Authorization", bearer(alice, tenantA)))
                .andExpect(status().isNoContent());   // idempotent
        mvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", bearer(alice, tenantA)))
                .andExpect(jsonPath("$.count", is(0)));
        list(bearer(alice, tenantA), "?unread=true").andExpect(jsonPath("$.content", hasSize(0)));
        list(bearer(alice, tenantA), "").andExpect(jsonPath("$.content[0].read", is(true)));
    }

    @Test
    void paginationAndValidation() throws Exception {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
        for (int i = 0; i < 5; i++) seed(tenant, user, "ticket.commented");
        list(bearer(user, tenant), "?page=1&size=2").andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalPages", is(3))).andExpect(jsonPath("$.totalElements", is(5)));
        list(bearer(user, tenant), "?page=2&size=2").andExpect(jsonPath("$.content", hasSize(1)));
        list(bearer(user, tenant), "?size=0").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED"))).andExpect(jsonPath("$.fieldErrors[0].field", is("size")));
        list(bearer(user, tenant), "?size=101").andExpect(status().isBadRequest());
        list(bearer(user, tenant), "?page=-1").andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/notifications/not-a-uuid/read").header("Authorization", bearer(user, tenant)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
    }
}
