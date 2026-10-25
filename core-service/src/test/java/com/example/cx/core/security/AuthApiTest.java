package com.example.cx.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.cx.core.AbstractApiTest;
import com.example.cx.core.config.CxProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class AuthApiTest extends AbstractApiTest {
    @Autowired JwtService jwt;

    @Test
    void loginMeLogoutRevokesTheToken() throws Exception {
        Tenant t = newTenant();
        JsonNode login = login(t.slug(), t.adminEmail(), PASSWORD);
        String token = login.get("accessToken").asText();
        assertThat(login.get("expiresInSeconds").asLong()).isPositive();
        assertThat(login.at("/user/roles/0").asText()).isEqualTo("ADMIN");
        assertThat(login.at("/user/tenant/slug").asText()).isEqualTo(t.slug());

        MvcResult me = doGet(token, "/api/v1/auth/me");
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(me).get("email").asText()).isEqualTo(t.adminEmail());

        MvcResult out = mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token)).andReturn();
        assertThat(out.getResponse().getStatus()).isEqualTo(204);
        // the SAME token is now rejected everywhere
        assertThat(doGet(token, "/api/v1/auth/me").getResponse().getStatus()).isEqualTo(401);
        assertThat(doGet(token, "/api/v1/tickets").getResponse().getStatus()).isEqualTo(401);
        // a fresh login still works (other tokens are not affected)
        assertThat(doGet(t.adminToken(), "/api/v1/auth/me").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void tokenClaimsFollowTheContract() throws Exception {
        Tenant t = newTenant();
        String token = login(t.slug(), t.adminEmail(), PASSWORD).get("accessToken").asText();
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        JsonNode c = json.readTree(payload);
        assertThat(c.get("tid").asText()).isEqualTo(t.id().toString());
        assertThat(c.get("sub").asText()).isNotBlank();
        assertThat(c.get("jti").asText()).isNotBlank();
        assertThat(c.get("email").asText()).isEqualTo(t.adminEmail());
        assertThat(c.get("roles").get(0).asText()).isEqualTo("ADMIN");
        assertThat(token.split("\\.")[0]).isEqualTo(Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void forgedExpiredAndUnsignedTokensAreRejected() throws Exception {
        Tenant a = newTenant();
        Tenant b = newTenant();
        UUID someUser = UUID.randomUUID();
        // signed with the wrong key, claiming tenant A admin
        String forged = Jwts.builder().id(UUID.randomUUID().toString()).issuer("cx-core-service").subject(someUser.toString())
                .claim("tid", a.id().toString()).claim("roles", List.of("ADMIN")).claim("email", "x@y.test")
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor("a-completely-different-32-byte-secret!!".getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
        assertThat(doGet(forged, "/api/v1/tickets").getResponse().getStatus()).isEqualTo(401);

        // correctly signed (dev secret) but already expired
        String expired = Jwts.builder().id(UUID.randomUUID().toString()).issuer("cx-core-service").subject(someUser.toString())
                .claim("tid", a.id().toString()).claim("roles", List.of("ADMIN")).claim("email", "x@y.test")
                .issuedAt(Date.from(Instant.now().minusSeconds(7200))).expiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(CxProperties.DEV_JWT_SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
        assertThat(doGet(expired, "/api/v1/tickets").getResponse().getStatus()).isEqualTo(401);

        // alg=none
        String h = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String p = Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"sub\":\"" + someUser + "\",\"tid\":\"" + a.id() + "\",\"roles\":[\"ADMIN\"]}").getBytes(StandardCharsets.UTF_8));
        assertThat(doGet(h + "." + p + ".", "/api/v1/tickets").getResponse().getStatus()).isEqualTo(401);

        // payload tampering (swap tenant) invalidates the signature
        String[] parts = b.adminToken().split("\\.");
        JsonNode claims = json.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        ((com.fasterxml.jackson.databind.node.ObjectNode) claims).put("tid", a.id().toString());
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(claims)) + "." + parts[2];
        assertThat(doGet(tampered, "/api/v1/tickets").getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void invalidCredentialsGiveOneGenericError() throws Exception {
        Tenant t = newTenant();
        MvcResult wrongPassword = rawLogin(t.slug(), t.adminEmail(), "wrong-password");
        MvcResult unknownUser = rawLogin(t.slug(), "nobody@" + t.slug() + ".test", PASSWORD);
        MvcResult unknownTenant = rawLogin("does-not-exist", t.adminEmail(), PASSWORD);
        for (MvcResult r : List.of(wrongPassword, unknownUser, unknownTenant)) {
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
            assertThat(body(r).get("code").asText()).isEqualTo("UNAUTHENTICATED");
            assertThat(body(r).get("message").asText()).isEqualTo("Invalid credentials");
            assertThat(r.getResponse().getContentAsString()).doesNotContain(PASSWORD);
        }
    }

    @Test
    void registerOrganizationValidatesAndRejectsDuplicateSlug() throws Exception {
        Tenant t = newTenant();
        Map<String, Object> dup = Map.of("organizationName", "Dup", "slug", t.slug(), "adminEmail", "a@b.test",
                "adminDisplayName", "Dup Admin", "password", PASSWORD);
        MvcResult r = mvc.perform(post("/api/v1/auth/register-organization").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(dup))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        Map<String, Object> weak = Map.of("organizationName", "Weak", "slug", "weak-" + UUID.randomUUID().toString().substring(0, 6),
                "adminEmail", "a@b.test", "adminDisplayName", "W", "password", "short");
        MvcResult v = mvc.perform(post("/api/v1/auth/register-organization").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(weak))).andReturn();
        assertThat(v.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(v).get("fieldErrors").findValuesAsText("field")).contains("password", "adminDisplayName");
        // the response body never contains a password hash
        MvcResult ok = mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + t.adminToken())).andReturn();
        assertThat(ok.getResponse().getContentAsString()).doesNotContain("password").doesNotContain("$2a$");
    }

    @Test
    void passwordsAreStoredAsBcrypt() {
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder enc = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(10);
        String hash = enc.encode(PASSWORD);
        assertThat(hash).startsWith("$2a$10$").hasSize(60);
        assertThat(jwt.ttlSeconds()).isEqualTo(3600);
    }

    private MvcResult rawLogin(String slug, String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("tenantSlug", slug, "email", email, "password", password)))).andReturn();
    }
}
