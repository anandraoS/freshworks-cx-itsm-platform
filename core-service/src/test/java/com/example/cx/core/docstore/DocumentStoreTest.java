package com.example.cx.core.docstore;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.cx.core.AbstractApiTest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The same behavioural contract is asserted for the JPA-table store (Spring bean, H2) and the in-memory store. */
class DocumentStoreTest extends AbstractApiTest {
    @Autowired DocumentStore jpaStore;

    static Map<String, Object> doc(String provider, double confidence) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("provider", provider);
        d.put("confidence", confidence);
        d.put("nested", Map.of("a", 1, "list", List.of("x", "y")));
        return d;
    }

    private void contract(DocumentStore store, UUID tenantA, UUID tenantB) {
        store.put(tenantA, "col", "k:0001", doc("MOCK", 0.5));
        store.put(tenantA, "col", "k:0002", doc("MOCK", 0.9));
        store.put(tenantA, "col", "other:0001", doc("HTTP", 0.2));
        store.put(tenantB, "col", "k:0001", doc("B-ONLY", 0.1));

        assertThat(store.get(tenantA, "col", "k:0001")).isPresent();
        assertThat(store.get(tenantA, "col", "k:0001").orElseThrow()).containsEntry("provider", "MOCK");
        assertThat(store.get(tenantA, "col", "k:0001").orElseThrow().get("nested")).isEqualTo(Map.of("a", 1, "list", List.of("x", "y")));
        assertThat(store.get(tenantA, "col", "nope")).isEmpty();
        assertThat(store.get(tenantA, "other-col", "k:0001")).isEmpty();

        // upsert replaces
        store.put(tenantA, "col", "k:0001", doc("MOCK", 0.7));
        assertThat(store.get(tenantA, "col", "k:0001").orElseThrow().get("confidence")).isEqualTo(0.7);

        // prefix listing is ascending by key, bounded, and tenant scoped
        List<Map<String, Object>> list = store.list(tenantA, "col", "k:", 10);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("confidence")).isEqualTo(0.7);
        assertThat(list.get(1).get("confidence")).isEqualTo(0.9);
        assertThat(store.list(tenantA, "col", "k:", 1)).hasSize(1);
        assertThat(store.list(tenantA, "col", "%", 10)).as("LIKE wildcards in a prefix are literals").isEmpty();
        assertThat(store.list(tenantB, "col", "k:", 10)).hasSize(1);
        assertThat(store.list(tenantB, "col", "k:", 10).get(0)).containsEntry("provider", "B-ONLY");
        assertThat(store.get(tenantB, "col", "other:0001")).as("same key in another tenant is invisible").isEmpty();

        // aggregation example: average confidence per provider (tenant scoped)
        Map<String, Double> avg = store.averageBy(tenantA, "col", "provider", "confidence");
        assertThat(avg).containsOnlyKeys("HTTP", "MOCK");
        assertThat(avg.get("MOCK")).isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(avg.get("HTTP")).isEqualTo(0.2);
        assertThat(store.averageBy(tenantB, "col", "provider", "confidence")).containsOnlyKeys("B-ONLY");
    }

    @Test
    void jpaTableStoreHonoursTheContract() throws Exception {
        assertThat(jpaStore).isInstanceOf(JpaDocumentStore.class);
        contract(jpaStore, newTenant().id(), newTenant().id());
    }

    @Test
    void inMemoryStoreHonoursTheContract() {
        contract(new InMemoryDocumentStore(), UUID.randomUUID(), UUID.randomUUID());
    }
}
