package com.example.cx.core.docstore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default DocumentStore: JSON text in the {@code document_store} table. Participates in the caller's transaction, so with
 * this implementation a KB version snapshot commits (or rolls back) atomically with the article change. The Mongo
 * implementation cannot offer that - see docs/kb-storage-notes.md.
 */
@Component
@ConditionalOnProperty(name = "cx.docstore", havingValue = "jpa", matchIfMissing = true)
public class JpaDocumentStore implements DocumentStore {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final DocumentEntityRepository repo;
    private final ObjectMapper mapper;

    public JpaDocumentStore(DocumentEntityRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void put(UUID tenantId, String collection, String key, Map<String, Object> doc) {
        String json;
        try {
            json = mapper.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Document is not serialisable", e);
        }
        if (json.length() > 32000) throw new IllegalArgumentException("Document too large for the JPA document store");
        DocumentEntity e = repo.findById(new DocumentEntity.Pk(tenantId, collection, key)).orElseGet(() -> {
            DocumentEntity n = new DocumentEntity();
            n.setTenantId(tenantId);
            n.setCollection(collection);
            n.setDocKey(key);
            n.setCreatedAt(Instant.now());
            return n;
        });
        e.setDocJson(json);
        repo.save(e);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> get(UUID tenantId, String collection, String key) {
        return repo.findById(new DocumentEntity.Pk(tenantId, collection, key)).filter(d -> d.getTenantId().equals(tenantId)).map(this::parse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID tenantId, String collection, String keyPrefix, int limit) {
        String like = keyPrefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return repo.byPrefix(tenantId, collection, like, PageRequest.of(0, DocumentStore.boundedLimit(limit))).stream().map(this::parse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Double> averageBy(UUID tenantId, String collection, String groupField, String valueField) {
        return DocAggregations.averageBy(list(tenantId, collection, "", DocumentStore.MAX_LIST), groupField, valueField);
    }

    private Map<String, Object> parse(DocumentEntity d) {
        try {
            return mapper.readValue(d.getDocJson(), MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt document " + d.getCollection() + "/" + d.getDocKey(), e);
        }
    }
}
