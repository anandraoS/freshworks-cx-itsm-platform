package com.example.cx.core.docstore;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the MongoDB DocumentStore (indexes, tenant scoping, prefix listing, $group/$avg aggregation) against a real MongoDB.
 * Tagged "docker": EXCLUDED from the default build and NOT EXECUTED in the authoring environment (no Docker available).
 * Run with:  mvn -pl core-service -am test -Pdocker-tests -Dtest=MongoDocumentStoreDockerIT
 */
@Tag("docker")
class MongoDocumentStoreDockerIT {
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));
    static MongoClient client;
    static MongoTemplate template;
    static MongoDocumentStore store;

    @BeforeAll
    static void start() {
        mongo.start();
        client = MongoClients.create(mongo.getConnectionString());
        template = new MongoTemplate(client, "cx_test");
        store = new MongoDocumentStore(template);
        store.ensureIndexes();
    }

    @AfterAll
    static void stop() {
        if (client != null) client.close();
        mongo.stop();
    }

    @Test
    void putGetListAreTenantScoped() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        store.put(a, "kb_article_version", "art1:00000001", Map.of("title", "v1", "version", 1));
        store.put(a, "kb_article_version", "art1:00000002", Map.of("title", "v2", "version", 2));
        store.put(b, "kb_article_version", "art1:00000001", Map.of("title", "other tenant", "version", 1));
        store.put(a, "kb_article_version", "art1:00000001", Map.of("title", "v1-edited", "version", 1));   // upsert

        assertThat(store.get(a, "kb_article_version", "art1:00000001").orElseThrow()).containsEntry("title", "v1-edited");
        assertThat(store.get(b, "kb_article_version", "art1:00000002")).isEmpty();
        List<Map<String, Object>> list = store.list(a, "kb_article_version", "art1:", 10);
        assertThat(list).extracting(m -> m.get("title")).containsExactly("v1-edited", "v2");
        assertThat(store.list(b, "kb_article_version", "art1:", 10)).hasSize(1);
        assertThat(store.list(a, "kb_article_version", ".*", 10)).as("prefix is quoted, not a regex").isEmpty();
    }

    @Test
    void uniqueIndexOnTenantCollectionKeyExists() {
        List<String> names = template.getCollection(MongoDocumentStore.COLLECTION).listIndexes().map(d -> d.getString("name")).into(new java.util.ArrayList<>());
        assertThat(names).contains("uq_tenant_collection_key", "ix_tenant_collection_provider", "ix_tenant_collection_article_version");
        Document unique = template.getCollection(MongoDocumentStore.COLLECTION).listIndexes().into(new java.util.ArrayList<>()).stream()
                .filter(d -> "uq_tenant_collection_key".equals(d.getString("name"))).findFirst().orElseThrow();
        assertThat(unique.getBoolean("unique")).isTrue();
    }

    @Test
    void aggregationAveragesConfidenceByProviderPerTenant() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        store.put(a, "ai_suggestion_meta", "s1", Map.of("provider", "MOCK_RULE_BASED", "confidence", 0.5));
        store.put(a, "ai_suggestion_meta", "s2", Map.of("provider", "MOCK_RULE_BASED", "confidence", 0.9));
        store.put(a, "ai_suggestion_meta", "s3", Map.of("provider", "HTTP", "confidence", 0.2));
        store.put(b, "ai_suggestion_meta", "s4", Map.of("provider", "MOCK_RULE_BASED", "confidence", 0.1));
        Map<String, Double> avg = store.averageBy(a, "ai_suggestion_meta", "provider", "confidence");
        assertThat(avg).containsOnlyKeys("HTTP", "MOCK_RULE_BASED");
        assertThat(avg.get("MOCK_RULE_BASED")).isCloseTo(0.7, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(store.averageBy(b, "ai_suggestion_meta", "provider", "confidence")).containsEntry("MOCK_RULE_BASED", 0.1);
    }
}
