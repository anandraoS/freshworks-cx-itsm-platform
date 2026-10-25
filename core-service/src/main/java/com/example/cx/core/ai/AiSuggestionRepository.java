package com.example.cx.core.ai;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every query carries an explicit tenant predicate (in addition to the Hibernate tenant filter and PG RLS). */
public interface AiSuggestionRepository extends JpaRepository<AiSuggestion, UUID> {
    Optional<AiSuggestion> findByIdAndTenantId(UUID id, UUID tenantId);

    List<AiSuggestion> findByTenantIdAndTicketIdOrderByCreatedAtDescIdDesc(UUID tenantId, UUID ticketId, Pageable limit);

    /**
     * Atomic compare-and-set PENDING_REVIEW -> decision. Exactly one of N concurrent decisions gets 1; everyone else gets 0
     * (=> 409). This is what makes "approve twice" / "approve and reject at once" impossible, without row locks.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AiSuggestion s set s.status = :to, s.decidedBy = :by, s.decidedAt = :at, s.decisionReason = :reason "
            + "where s.id = :id and s.tenantId = :t and s.status = com.example.cx.core.ai.SuggestionStatus.PENDING_REVIEW")
    int decide(@Param("id") UUID id, @Param("t") UUID tenantId, @Param("to") SuggestionStatus to, @Param("by") UUID by,
               @Param("at") Instant at, @Param("reason") String reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AiSuggestion s set s.postedCommentId = :c where s.id = :id and s.tenantId = :t")
    int attachComment(@Param("id") UUID id, @Param("t") UUID tenantId, @Param("c") UUID commentId);

    @Query("select count(s) from AiSuggestion s where s.tenantId = :t and s.status in :statuses and s.decidedAt >= :from")
    long countDecided(@Param("t") UUID tenantId, @Param("statuses") Collection<SuggestionStatus> statuses, @Param("from") Instant from);
}
