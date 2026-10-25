package com.example.cx.core.kb;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.cx.core.AbstractApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "cx.seed.enabled=true")
class KbSeedTest extends AbstractApiTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void everyDemoTenantHasTwelveSyntheticArticlesWithTheExpectedMix() throws Exception {
        for (String slug : new String[] {"acme-retail", "globex-it", "initech-health"}) {
            String tenant = jdbc.queryForObject("select id from organization where slug = ?", String.class, slug);
            assertThat(jdbc.queryForObject("select count(*) from kb_article where tenant_id = cast(? as uuid)", Long.class, tenant)).as(slug).isEqualTo(12L);
            assertThat(jdbc.queryForObject("select count(*) from kb_article where tenant_id = cast(? as uuid) and status = 'DRAFT'", Long.class, tenant)).isEqualTo(2L);
            assertThat(jdbc.queryForObject("select count(*) from kb_article where tenant_id = cast(? as uuid) and visibility = 'INTERNAL'", Long.class, tenant)).isEqualTo(3L);
            assertThat(jdbc.queryForObject("select count(*) from kb_category where tenant_id = cast(? as uuid)", Long.class, tenant)).isEqualTo(6L);
            assertThat(jdbc.queryForObject("select count(*) from document_store where tenant_id = cast(? as uuid) and collection = 'kb_article_version'", Long.class, tenant))
                    .as("one v1 snapshot per article").isEqualTo(12L);
        }
    }

    @Test
    void seededVisibilityRulesHoldThroughTheApi() throws Exception {
        String customer = login("acme-retail", "customer1@acme.test", "Passw0rd!demo").get("accessToken").asText();
        String agent = login("acme-retail", "agent1@acme.test", "Passw0rd!demo").get("accessToken").asText();
        JsonNode cust = body(doGet(customer, "/api/v1/articles?size=100"));
        assertThat(cust.get("totalElements").asInt()).isEqualTo(8);
        cust.get("content").forEach(a -> {
            assertThat(a.get("status").asText()).isEqualTo("PUBLISHED");
            assertThat(a.get("visibility").asText()).isEqualTo("PUBLIC");
        });
        assertThat(body(doGet(agent, "/api/v1/articles?size=100")).get("totalElements").asInt()).isEqualTo(12);
        assertThat(body(doGet(agent, "/api/v1/articles?q=vpn&size=5")).get("content").get(0).get("title").asText()).contains("VPN");
        assertThat(body(doGet(customer, "/api/v1/article-categories")).size()).isEqualTo(6);
        // a globex agent sees only globex articles
        String globex = login("globex-it", "agent1@globex.test", "Passw0rd!demo").get("accessToken").asText();
        assertThat(body(doGet(globex, "/api/v1/articles?size=100")).get("totalElements").asInt()).isEqualTo(12);
    }
}
