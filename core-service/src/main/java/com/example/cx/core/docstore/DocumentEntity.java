package com.example.cx.core.docstore;

import com.example.cx.core.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

/** Row of the JPA-table DocumentStore. Tenant-filtered like every tenant-owned entity (explicit predicates are used as well). */
@Entity
@Table(name = "document_store")
@IdClass(DocumentEntity.Pk.class)
@Filter(name = TenantOwned.FILTER, condition = TenantOwned.FILTER_CONDITION)
@Getter
@Setter
@NoArgsConstructor
public class DocumentEntity {
    @Id @Column(name = "tenant_id") private UUID tenantId;
    @Id @Column(name = "collection", length = 40) private String collection;
    @Id @Column(name = "doc_key", length = 120) private String docKey;
    @Column(name = "doc_json", nullable = false, length = 32000) private String docJson;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    public static class Pk implements Serializable {
        private UUID tenantId;
        private String collection;
        private String docKey;
        public Pk() {}
        public Pk(UUID t, String c, String k) { tenantId = t; collection = c; docKey = k; }
        @Override public boolean equals(Object o) {
            return o instanceof Pk p && Objects.equals(tenantId, p.tenantId) && Objects.equals(collection, p.collection) && Objects.equals(docKey, p.docKey);
        }
        @Override public int hashCode() { return Objects.hash(tenantId, collection, docKey); }
    }
}
