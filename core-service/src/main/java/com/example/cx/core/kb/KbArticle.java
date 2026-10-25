package com.example.cx.core.kb;

import com.example.cx.core.tenancy.TenantOwned;
import com.example.cx.core.tickets.Visibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

/**
 * AUTHORITATIVE state of an article (metadata + current content). {@code @Version} = optimistic lock; the public article
 * "version" is {@code entityVersion + 1} (v1 on creation) and every committed change produces one snapshot in the DocumentStore.
 */
@Entity
@Table(name = "kb_article")
@Filter(name = TenantOwned.FILTER, condition = TenantOwned.FILTER_CONDITION)
@Getter
@Setter
@NoArgsConstructor
public class KbArticle extends TenantOwned {
    @Id
    private UUID id;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(nullable = false, length = 220)
    private String slug;
    @Column(nullable = false, length = 500)
    private String summary = "";
    @Column(nullable = false, length = 15000)
    private String body;
    @Column(name = "category_id")
    private UUID categoryId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Visibility visibility;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ArticleStatus status = ArticleStatus.DRAFT;
    @Column(name = "author_id", nullable = false)
    private UUID authorId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "published_at")
    private Instant publishedAt;
    @Version
    private Long version;

    /** Public, human-facing version number (1-based). */
    public long displayVersion() { return (version == null ? 0 : version) + 1; }
}
