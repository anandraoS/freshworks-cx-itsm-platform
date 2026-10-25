package com.example.cx.core.kb;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every query carries an explicit tenant predicate (in addition to the Hibernate tenant filter and PG RLS). */
public interface KbArticleRepository extends JpaRepository<KbArticle, UUID>, JpaSpecificationExecutor<KbArticle> {
    Optional<KbArticle> findByIdAndTenantId(UUID id, UUID tenantId);
    boolean existsByTenantIdAndSlug(UUID tenantId, String slug);
    long countByTenantIdAndCategoryId(UUID tenantId, UUID categoryId);

    /** Narrow projection for AI recommendation: published articles only (title/summary, no body). */
    @Query("select new com.example.cx.core.kb.KbCandidate(a.id, a.title, a.summary, a.visibility, a.categoryId) "
            + "from KbArticle a where a.tenantId = :t and a.status = com.example.cx.core.kb.ArticleStatus.PUBLISHED order by a.updatedAt desc, a.id asc")
    List<KbCandidate> publishedCandidates(@Param("t") UUID tenantId, Pageable limit);
}
