package com.example.cx.core.docstore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Volatile store for unit tests / throw-away demos (cx.docstore=memory). Not durable, not shared between nodes. */
@Component
@ConditionalOnProperty(name = "cx.docstore", havingValue = "memory")
public class InMemoryDocumentStore implements DocumentStore {
    private final Map<String, TreeMap<String, Map<String, Object>>> data = new ConcurrentHashMap<>();

    private static String bucket(UUID tenant, String collection) { return tenant + "|" + collection; }

    @Override
    public void put(UUID tenantId, String collection, String key, Map<String, Object> doc) {
        TreeMap<String, Map<String, Object>> b = data.computeIfAbsent(bucket(tenantId, collection), k -> new TreeMap<>());
        synchronized (b) { b.put(key, new LinkedHashMap<>(doc)); }
    }

    @Override
    public Optional<Map<String, Object>> get(UUID tenantId, String collection, String key) {
        TreeMap<String, Map<String, Object>> b = data.get(bucket(tenantId, collection));
        if (b == null) return Optional.empty();
        synchronized (b) { return Optional.ofNullable(b.get(key)).map(LinkedHashMap::new); }
    }

    @Override
    public List<Map<String, Object>> list(UUID tenantId, String collection, String keyPrefix, int limit) {
        TreeMap<String, Map<String, Object>> b = data.get(bucket(tenantId, collection));
        if (b == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        int max = DocumentStore.boundedLimit(limit);
        synchronized (b) {
            for (var e : b.tailMap(keyPrefix, true).entrySet()) {
                if (!e.getKey().startsWith(keyPrefix) || out.size() >= max) break;
                out.add(new LinkedHashMap<>(e.getValue()));
            }
        }
        return out;
    }

    @Override
    public Map<String, Double> averageBy(UUID tenantId, String collection, String groupField, String valueField) {
        return DocAggregations.averageBy(list(tenantId, collection, "", DocumentStore.MAX_LIST), groupField, valueField);
    }
}
