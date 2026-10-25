package com.example.cx.core.tickets;

import com.example.cx.core.tickets.TicketDtos.TicketQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Builds the list/filter specification. The tenant predicate is ALWAYS the first predicate. */
final class TicketSearch {
    private TicketSearch() {}

    static Specification<Ticket> build(UUID tenantId, UUID currentUserId, boolean customerOnly, TicketQuery q,
                                       boolean sortByPriority, boolean priorityDesc) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("tenantId"), tenantId));
            if (customerOnly) {
                ps.add(cb.equal(root.get("requesterId"), currentUserId)); // customers: own tickets, never overridable
            } else if (q.mine()) {
                ps.add(cb.or(cb.equal(root.get("assigneeId"), currentUserId), cb.equal(root.get("requesterId"), currentUserId)));
            }
            if (q.status() != null) ps.add(cb.equal(root.get("status"), q.status()));
            if (q.priority() != null) ps.add(cb.equal(root.get("priority"), q.priority()));
            if (q.assigneeId() != null) ps.add(cb.equal(root.get("assigneeId"), q.assigneeId()));
            if (q.categoryId() != null) ps.add(cb.equal(root.get("categoryId"), q.categoryId()));
            if (q.type() != null) ps.add(cb.equal(root.get("type"), q.type()));
            if (q.slaStatus() != null) ps.add(cb.equal(root.get("slaStatus"), q.slaStatus()));
            if (q.q() != null && !q.q().isBlank()) {
                String needle = q.q().trim();
                String like = "%" + escape(needle.toLowerCase()) + "%";
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(root.get("title")), like, '\\'));
                any.add(cb.like(cb.lower(root.get("description")), like, '\\'));
                String digits = needle.startsWith("#") ? needle.substring(1) : needle;
                if (digits.matches("\\d{1,15}")) any.add(cb.equal(root.get("number"), Long.parseLong(digits)));
                ps.add(cb.or(any.toArray(Predicate[]::new)));
            }
            if (sortByPriority && query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
                CriteriaBuilder.SimpleCase<Priority, Integer> rank = cb.selectCase(root.<Priority>get("priority"));
                Expression<Integer> r = rank.when(Priority.LOW, 0).when(Priority.MEDIUM, 1).when(Priority.HIGH, 2).when(Priority.URGENT, 3).otherwise(-1);
                query.orderBy(priorityDesc ? cb.desc(r) : cb.asc(r), cb.asc(root.get("id")));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
    }

    static String escape(String s) { return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }
}
