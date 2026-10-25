package com.example.cx.core.workflow;

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

/** One immutable VERSION of a tenant workflow. Exactly one version per tenant is active at a time. */
@Entity
@Table(name = "workflow")
@Filter(name = TenantOwned.FILTER, condition = TenantOwned.FILTER_CONDITION)
@Getter
@Setter
@NoArgsConstructor
public class Workflow extends TenantOwned {
    @Id
    private UUID id;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(nullable = false)
    private int version;
    @Column(nullable = false)
    private boolean active;
    /** JSON of {@link WorkflowModel.Definition} (rules + transitions). Data only. */
    @Column(nullable = false, length = 20000)
    private String definition;
    @Column(name = "created_by")
    private UUID createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
