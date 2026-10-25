package com.example.cx.core.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.cx.core.AbstractApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CacheIntegrationTest extends AbstractApiTest {
    @Autowired MeterRegistry meters;

    private double count(String cache, String result) {
        var c = meters.find("cx.cache").tags("cache", cache, "result", result).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void categoriesAreCachedAndInvalidatedOnWrite() throws Exception {
        Tenant t = newTenant();
        double hits = count("categories", "hit"), misses = count("categories", "miss");
        JsonNode first = body(doGet(t.adminToken(), "/api/v1/ticket-categories"));
        JsonNode second = body(doGet(t.adminToken(), "/api/v1/ticket-categories"));
        assertThat(second).isEqualTo(first);
        assertThat(count("categories", "miss")).isEqualTo(misses + 1);
        assertThat(count("categories", "hit")).isEqualTo(hits + 1);

        assertThat(doPost(t.adminToken(), "/api/v1/ticket-categories",
                Map.of("name", "Brand New", "type", "SUPPORT", "defaultPriority", "LOW")).getResponse().getStatus()).isEqualTo(201);
        JsonNode third = body(doGet(t.adminToken(), "/api/v1/ticket-categories"));
        assertThat(third.size()).isEqualTo(first.size() + 1); // stale list was evicted by the write
        assertThat(third.findValuesAsText("name")).contains("Brand New");
    }

    @Test
    void dashboardIsCachedPerTenantAndInvalidatedByTicketWrites() throws Exception {
        Tenant t = newTenant();
        double hits = count("analytics", "hit");
        assertThat(body(doGet(t.adminToken(), "/api/v1/analytics/dashboard")).get("openTickets").asInt()).isZero();
        assertThat(body(doGet(t.adminToken(), "/api/v1/analytics/dashboard")).get("openTickets").asInt()).isZero();
        assertThat(count("analytics", "hit")).isEqualTo(hits + 1);
        // a write evicts (after commit) -> next read is fresh, not 60s stale
        createTicket(t.adminToken(), "Invalidates dashboard", "LOW");
        assertThat(body(doGet(t.adminToken(), "/api/v1/analytics/dashboard")).get("openTickets").asInt()).isEqualTo(1);
        // different "days" window = different key
        assertThat(body(doGet(t.adminToken(), "/api/v1/analytics/dashboard?days=7")).get("resolutionTrend").size()).isEqualTo(7);
        assertThat(doGet(t.adminToken(), "/api/v1/analytics/dashboard?days=0").getResponse().getStatus()).isEqualTo(400);
        assertThat(doGet(t.adminToken(), "/api/v1/analytics/dashboard?days=366").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void settingsUpdateEvictsTheCalendarCache() throws Exception {
        Tenant t = newTenant();
        JsonNode settings = body(doGet(t.adminToken(), "/api/v1/tenant/settings"));
        assertThat(settings.get("timezone").asText()).isEqualTo("UTC");
        Map<String, Object> upd = Map.of("name", "Renamed", "timezone", "Asia/Kolkata", "businessHoursStart", "10:00", "businessHoursEnd", "18:00",
                "businessDays", java.util.List.of("MON", "TUE", "WED", "THU", "FRI"), "defaultLanguage", "en",
                "integrations", Map.of("emailProvider", "SMTP", "slackWebhookUrl", "https://hooks.example.test/x", "aiProvider", "MOCK"));
        assertThat(doPut(t.adminToken(), "/api/v1/tenant/settings", upd).getResponse().getStatus()).isEqualTo(200);
        assertThat(body(doGet(t.adminToken(), "/api/v1/tenant/settings")).get("timezone").asText()).isEqualTo("Asia/Kolkata");
        // invalid input is rejected
        Map<String, Object> bad = new java.util.HashMap<>(upd);
        bad.put("timezone", "Mars/Olympus");
        assertThat(doPut(t.adminToken(), "/api/v1/tenant/settings", bad).getResponse().getStatus()).isEqualTo(400);
        bad.put("timezone", "UTC");
        bad.put("integrations", Map.of("emailProvider", "SMTP", "slackWebhookUrl", "http://insecure.example.test", "aiProvider", "MOCK"));
        assertThat(doPut(t.adminToken(), "/api/v1/tenant/settings", bad).getResponse().getStatus()).isEqualTo(400); // https only
    }
}
