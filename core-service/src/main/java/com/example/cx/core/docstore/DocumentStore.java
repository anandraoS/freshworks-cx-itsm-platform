package com.example.cx.core.docstore;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * EXTENSION POINT: storage for FLEXIBLE, schema-less documents that are NOT the source of truth (KB version snapshots,
 * AI suggestion metadata). Authoritative state stays in the relational tables; see docs/kb-storage-notes.md.
 *
 * Every operation is tenant-scoped (the tenant is an explicit argument; implementations must never return another tenant's
 * document, even for an identical collection/key). Keys are plain strings; {@link #list} returns documents whose key starts
 * with a prefix in ascending key order (so zero-padded version numbers sort naturally).
 *
 * Implementations: {@code JpaDocumentStore} (default, table document_store), {@code InMemoryDocumentStore}
 * (cx.docstore=memory, unit tests), {@code MongoDocumentStore} (cx.docstore=mongo, docker profile).
 */
public interface DocumentStore {
    int MAX_LIST = 500;

    /** Upsert. */
    void put(UUID tenantId, String collection, String key, Map<String, Object> doc);

    Optional<Map<String, Object>> get(UUID tenantId, String collection, String key);

    /** Documents of the collection whose key starts with {@code keyPrefix}, ascending by key, at most {@code limit} (1..500). */
    List<Map<String, Object>> list(UUID tenantId, String collection, String keyPrefix, int limit);

    /**
     * Aggregation example: average of the numeric field {@code valueField} grouped by the string field {@code groupField}
     * over a collection (of one tenant). Mongo runs a {@code $match/$group/$avg} pipeline server-side; the SQL / in-memory
     * implementations fold a bounded number of documents in the JVM.
     */
    Map<String, Double> averageBy(UUID tenantId, String collection, String groupField, String valueField);

    static int boundedLimit(int limit) { return Math.max(1, Math.min(limit, MAX_LIST)); }
}
