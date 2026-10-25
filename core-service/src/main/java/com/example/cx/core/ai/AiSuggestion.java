package com.example.cx.core.ai;

import com.example.cx.core.tenancy.TenantOwned;
import com.example.cx.core.tickets.Priority;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

/**
 * AUTHORITATIVE record of a suggestion: tenant, ticket, status, decision. The flexible analysis metadata (features, scores,
 * prompt hash, provider details) is in the DocumentStore collection {@code ai_suggestion_meta}, keyed by this id.
 * Status transitions happen ONLY through the compare-and-set updates in {@link AiSuggestionRepository}.
 */
@Entity
@Table(name = "ai_suggestion")
@Filter(name = TenantOwned.FILTER, condition = TenantOwned.FILTER_CONDITION)
@Getter
@Setter
@NoArgsConstructor
public class AiSuggestion extends TenantOwned {
    @Id
    private UUID id;
    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SuggestionStatus status = SuggestionStatus.PENDING_REVIEW;
    @Column(nullable = false, length = 30)
    private String provider;
    @Column(nullable = false, length = 2000)
    private String summary;
    @Column(name = "suggested_category_id")
    private UUID suggestedCategoryId;
    @Column(name = "suggested_category_name", length = 80)
    private String suggestedCategoryName;
    @Enumerated(EnumType.STRING)
    @Column(name = "suggested_priority", length = 10)
    private Priority suggestedPriority;
    @Column(nullable = false)
    private double confidence;
    @Column(nullable = false, length = 2000)
    private String explanation;
    /** JSON array [{id,title,score}] (small, bounded to 3 entries). */
    @Column(name = "recommended_articles", nullable = false, length = 2000)
    private String recommendedArticles = "[]";
    @Column(name = "suggested_reply", length = 4000)
    private String suggestedReply;
    @Column(name = "created_by", nullable = false)
    private UUID createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "decided_by")
    private UUID decidedBy;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Column(name = "decision_reason", length = 500)
    private String decisionReason;
    @Column(name = "posted_comment_id")
    private UUID postedCommentId;
}
