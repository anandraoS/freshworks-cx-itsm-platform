package com.example.cx.core.docstore;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * MongoDB DocumentStore (docker profile, {@code cx.docstore=mongo}). NOT executed in the authoring environment (no Docker);
 * {@code MongoDocumentStoreDockerIT} (tag docker) is the test that proves it on a machine with Docker.
 *
 * One physical collection {@value #COLLECTION}; every document carries {@code tenantId}, {@code collection} (logical name) and
 * {@code key}; the payload is nested under {@code doc} so user-supplied field names can never clash with the envelope.
 * EVERY query filters on tenantId first (there is no tenant-less read path), matching the relational tenant-scoping rule.
 *
 * Indexes (created at startup, idempotent):
 *  1. unique (tenantId, collection, key)            - point lookups, upsert identity, and key-prefix range scans (prefix regex is anchored)
 *  2. (tenantId, collection, doc.provider)          - grouping/filtering AI metadata by provider (aggregation example below)
 *  3. (tenantId, collection, doc.articleId, doc.version desc) - "versions of article X, newest first"
 */
@Component
@ConditionalOnProperty(name = "cx.docstore", havingValue = "mongo")
public class MongoDocumentStore implements DocumentStore {
    public static final String COLLECTION = "cx_documents";
    private final MongoTemplate mongo;

    public MongoDocumentStore(MongoTemplate mongo) { this.mongo = mongo; }

    @PostConstruct
    void ensureIndexes() {
        var ops = mongo.indexOps(COLLECTION);
        ops.ensureIndex(new Index().on("tenantId", Sort.Direction.ASC).on("collection", Sort.Direction.ASC)
                .on("key", Sort.Direction.ASC).unique().named("uq_tenant_collection_key"));
        ops.ensureIndex(new Index().on("tenantId", Sort.Direction.ASC).on("collection", Sort.Direction.ASC)
                .on("doc.provider", Sort.Direction.ASC).named("ix_tenant_collection_provider"));
        ops.ensureIndex(new Index().on("tenantId", Sort.Direction.ASC).on("collection", Sort.Direction.ASC)
                .on("doc.articleId", Sort.Direction.ASC).on("doc.version", Sort.Direction.DESC).named("ix_tenant_collection_article_version"));
    }

    @Override
    public void put(UUID tenantId, String collection, String key, Map<String, Object> doc) {
        Query q = identity(tenantId, collection, key);
        Update u = new Update().set("doc", new Document(doc)).setOnInsert("createdAt", Instant.now()).set("updatedAt", Instant.now());
        mongo.upsert(q, u, COLLECTION);
    }

    @Override
    public Optional<Map<String, Object>> get(UUID tenantId, String collection, String key) {
        Document d = mongo.findOne(identity(tenantId, collection, key), Document.class, COLLECTION);
        return Optional.ofNullable(d).map(MongoDocumentStore::payload);
    }

    @Override
    public List<Map<String, Object>> list(UUID tenantId, String collection, String keyPrefix, int limit) {
        Query q = new Query(Criteria.where("tenantId").is(tenantId.toString()).and("collection").is(collection)
                .and("key").regex("^" + Pattern.quote(keyPrefix)))
                .with(Sort.by(Sort.Direction.ASC, "key")).limit(DocumentStore.boundedLimit(limit));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Document d : mongo.find(q, Document.class, COLLECTION)) out.add(payload(d));
        return out;
    }

    /** Aggregation example: $match (tenant + logical collection) -> $group by doc.{groupField} with $avg of doc.{valueField}. */
    @Override
    public Map<String, Double> averageBy(UUID tenantId, String collection, String groupField, String valueField) {
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("tenantId").is(tenantId.toString()).and("collection").is(collection)),
                Aggregation.group("doc." + groupField).avg("doc." + valueField).as("avg"),
                Aggregation.sort(Sort.Direction.ASC, "_id"));
        Map<String, Double> out = new LinkedHashMap<>();
        for (Document d : mongo.aggregate(agg, COLLECTION, Document.class).getMappedResults()) {
            Object id = d.get("_id"), avg = d.get("avg");
            if (id != null && avg instanceof Number n) out.put(String.valueOf(id), n.doubleValue());
        }
        return out;
    }

    private static Query identity(UUID tenantId, String collection, String key) {
        return new Query(Criteria.where("tenantId").is(tenantId.toString()).and("collection").is(collection).and("key").is(key));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Document d) {
        Object p = d.get("doc");
        return p instanceof Document pd ? new LinkedHashMap<>(pd) : p instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
    }
}
