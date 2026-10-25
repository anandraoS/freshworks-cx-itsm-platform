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

/** Article <-> ticket link (many-to-many); composite FKs keep both sides inside the tenant. */
@Entity
@Table(name = "kb_article_ticket")
@Filter(name = TenantOwned.FILTER, condition = TenantOwned.FILTER_CONDITION)
@Getter
@Setter
@NoArgsConstructor
public class KbArticleTicket extends TenantOwned {
    @Id
    private UUID id;
    @Column(name = "article_id", nullable = false)
    private UUID articleId;
    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;
    @Column(name = "linked_by", nullable = false)
    private UUID linkedBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
