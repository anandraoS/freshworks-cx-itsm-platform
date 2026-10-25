package com.example.cx.core.tickets;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.cx.core.AbstractApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * N+1 guard: the number of SQL statements for a ticket list must NOT grow with the page size. Measured with Hibernate
 * statistics (generate_statistics=true in the test profile).
 */
class NPlusOneGuardTest extends AbstractApiTest {
    @Autowired EntityManagerFactory emf;

    private long statementsFor(String token, String path) throws Exception {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        var r = doGet(token, path);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return stats.getPrepareStatementCount();
    }

    @Test
    void ticketListQueryCountIsConstantRegardlessOfPageSize() throws Exception {
        Tenant t = newTenant();
        String agent = userToken(t, "agent", "AGENT");
        String cust = userToken(t, "cust", "CUSTOMER");
        String cat = body(doGet(agent, "/api/v1/ticket-categories")).get(0).get("id").asText();
        for (int i = 0; i < 30; i++) {
            Map<String, Object> b = new HashMap<>(ticketBody("N+1 probe " + i, i % 3 == 0 ? "HIGH" : "LOW")); // HIGH -> workflow assigns an agent
            b.put("categoryId", cat);
            b.put("attachments", List.of(Map.of("fileName", "f" + i + ".txt", "contentType", "text/plain", "sizeBytes", 10 + i)));
            var r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/tickets").header("Authorization", "Bearer " + (i % 2 == 0 ? cust : agent))
                    .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(b))).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(201);
        }
        doGet(agent, "/api/v1/tickets?size=2"); // warm up (first-use metadata/caches)

        long small = statementsFor(agent, "/api/v1/tickets?size=2&page=0");
        long large = statementsFor(agent, "/api/v1/tickets?size=30&page=0");
        JsonNode page = body(doGet(agent, "/api/v1/tickets?size=30"));
        assertThat(page.get("content")).hasSize(30);
        assertThat(page.get("content").get(0).get("attachments")).isNotEmpty();
        assertThat(page.get("content").findValuesAsText("categoryName")).doesNotContain("null");

        // page query + count + users IN + categories IN + attachments IN (+ the actor lookup) - independent of page size
        assertThat(large).as("30 rows must not cost more statements than 2 rows (small=%d, large=%d)", small, large).isEqualTo(small);
        assertThat(large).isLessThanOrEqualTo(8);
        // single ticket + comments + activity are also constant
        String id = page.get("content").get(0).get("id").asText();
        assertThat(statementsFor(agent, "/api/v1/tickets/" + id)).isLessThanOrEqualTo(6);
    }

    @Test
    void customerListIsAlsoConstant() throws Exception {
        Tenant t = newTenant();
        String cust = userToken(t, "cust", "CUSTOMER");
        for (int i = 0; i < 12; i++) createTicket(cust, "Own " + i, "LOW");
        doGet(cust, "/api/v1/tickets?size=2");
        long small = statementsFor(cust, "/api/v1/tickets?size=2");
        long large = statementsFor(cust, "/api/v1/tickets?size=12");
        assertThat(large).isEqualTo(small);
    }
}
