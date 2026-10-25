package com.example.cx.core.kb;

import com.example.cx.core.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "kb_category")
@Filter(name = TenantOwned.FILTER, condition = TenantOwned.FILTER_CONDITION)
@Getter
@Setter
@NoArgsConstructor
public class KbCategory extends TenantOwned {
    @Id
    private UUID id;
    @Column(nullable = false, length = 80)
    private String name;
    @Column(nullable = false, length = 100)
    private String slug;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
