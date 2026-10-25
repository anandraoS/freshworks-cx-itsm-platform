package com.example.cx.core.tickets;

import com.example.cx.core.analytics.TicketAgg;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every query carries an explicit tenant predicate (in addition to the Hibernate tenant filter and PG RLS). */
public interface TicketRepository extends JpaRepository<Ticket, UUID>, JpaSpecificationExecutor<Ticket> {
    Optional<Ticket> findByIdAndTenantId(UUID id, UUID tenantId);

    long countByTenantIdAndStatusNotIn(UUID tenantId, Collection<TicketStatus> statuses);

    long countByTenantIdAndStatusNotInAndCreatedAtBefore(UUID tenantId, Collection<TicketStatus> statuses, Instant before);

    long countByTenantIdAndCategoryId(UUID tenantId, UUID categoryId);

    long countByTenantIdAndCreatedAtGreaterThanEqual(UUID tenantId, Instant from);

    @Query("select t.priority, count(t) from Ticket t where t.tenantId = :t and t.status not in :done group by t.priority")
    List<Object[]> openByPriority(@Param("t") UUID tenantId, @Param("done") Collection<TicketStatus> done);

    @Query("select t.assigneeId, count(t) from Ticket t where t.tenantId = :t and t.assigneeId is not null "
            + "and t.status not in :done group by t.assigneeId order by count(t) desc")
    List<Object[]> openByAssignee(@Param("t") UUID tenantId, @Param("done") Collection<TicketStatus> done, Pageable limit);

    @Query("select t.assigneeId, count(t) from Ticket t where t.tenantId = :t and t.assigneeId in :ids "
            + "and t.status not in :done group by t.assigneeId")
    List<Object[]> openCountsFor(@Param("t") UUID tenantId, @Param("ids") Collection<UUID> ids,
                                 @Param("done") Collection<TicketStatus> done);

    /** Bounded projection feeding the live analytics aggregation (see AnalyticsService). */
    @Query("select new com.example.cx.core.analytics.TicketAgg(t.createdAt, t.firstRespondedAt, t.resolvedAt, t.slaStatus, t.reopenCount) "
            + "from Ticket t where t.tenantId = :t and (t.createdAt >= :from or t.resolvedAt >= :from)")
    List<TicketAgg> aggregates(@Param("t") UUID tenantId, @Param("from") Instant from, Pageable limit);

    /**
     * Least-recently-checked open tickets first (never checked = epoch): fair rotation across runs, bounded by Pageable.
     * COALESCE instead of NULLS FIRST on purpose: null ordering differs between databases / dialect settings.
     */
    @Query("select t from Ticket t where t.tenantId = :t and t.status not in :done "
            + "order by coalesce(t.slaCheckedAt, :epoch) asc, t.id asc")
    List<Ticket> slaScanBatch(@Param("t") UUID tenantId, @Param("done") Collection<TicketStatus> done,
                              @Param("epoch") Instant epoch, Pageable limit);

    /** Bulk UPDATE: does not touch the optimistic-lock version, so a background SLA check never causes user 409s. */
    @Modifying
    @Query("update Ticket t set t.slaStatus = :s, t.escalationLevel = :lvl, t.breachNotified = :b, t.slaCheckedAt = :at "
            + "where t.id = :id and t.tenantId = :t")
    int updateSlaState(@Param("id") UUID id, @Param("t") UUID tenantId, @Param("s") SlaStatus s,
                       @Param("lvl") int level, @Param("b") boolean breachNotified, @Param("at") Instant at);
}
