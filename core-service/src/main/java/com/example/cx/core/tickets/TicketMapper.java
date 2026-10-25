package com.example.cx.core.tickets;

import com.example.cx.core.identity.AppUser;
import com.example.cx.core.identity.UserRepository;
import com.example.cx.core.tenancy.TenantContext;
import com.example.cx.core.tickets.TicketDtos.AttachmentDto;
import com.example.cx.core.tickets.TicketDtos.TicketDto;
import com.example.cx.core.tickets.TicketDtos.UserRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Entity -> DTO assembly with BATCH lookups: a page of N tickets costs a constant number of queries
 * (1 users IN, 1 categories IN, 1 attachments IN) regardless of N. This is the N+1 guard; it is asserted in tests with
 * Hibernate statistics.
 */
@Component
public class TicketMapper {
    private final UserRepository users;
    private final TicketCategoryRepository categories;
    private final TicketAttachmentRepository attachments;

    public TicketMapper(UserRepository users, TicketCategoryRepository categories, TicketAttachmentRepository attachments) {
        this.users = users;
        this.categories = categories;
        this.attachments = attachments;
    }

    public TicketDto toDto(Ticket t) { return toDtos(List.of(t)).get(0); }

    public List<TicketDto> toDtos(List<Ticket> ts) {
        if (ts.isEmpty()) return List.of();
        UUID tenant = TenantContext.require();
        Set<UUID> userIds = new HashSet<>();
        Set<UUID> categoryIds = new HashSet<>();
        List<UUID> ticketIds = new ArrayList<>();
        for (Ticket t : ts) {
            userIds.add(t.getRequesterId());
            if (t.getAssigneeId() != null) userIds.add(t.getAssigneeId());
            if (t.getCategoryId() != null) categoryIds.add(t.getCategoryId());
            ticketIds.add(t.getId());
        }
        Map<UUID, String> userNames = users.findByTenantIdAndIdIn(tenant, userIds).stream()
                .collect(Collectors.toMap(AppUser::getId, AppUser::getDisplayName));
        Map<UUID, String> categoryNames = new HashMap<>();
        if (!categoryIds.isEmpty()) categories.findByTenantIdAndIdIn(tenant, categoryIds).forEach(c -> categoryNames.put(c.getId(), c.getName()));
        Map<UUID, List<AttachmentDto>> byTicket = new HashMap<>();
        attachments.findByTenantIdAndTicketIdInOrderByCreatedAtAscIdAsc(tenant, ticketIds).forEach(a ->
                byTicket.computeIfAbsent(a.getTicketId(), k -> new ArrayList<>())
                        .add(new AttachmentDto(a.getId(), a.getFileName(), a.getContentType(), a.getSizeBytes())));
        return ts.stream().map(t -> new TicketDto(t.getId(), t.getNumber(), t.getType(), t.getTitle(), t.getDescription(),
                t.getStatus(), t.getPriority(), t.getSeverity(), t.getCategoryId(), categoryNames.get(t.getCategoryId()),
                t.tagList(),
                new UserRef(t.getRequesterId(), userNames.getOrDefault(t.getRequesterId(), "Unknown")),
                t.getAssigneeId() == null ? null : new UserRef(t.getAssigneeId(), userNames.getOrDefault(t.getAssigneeId(), "Unknown")),
                t.getCreatedAt(), t.getUpdatedAt(), t.getFirstResponseDueAt(), t.getResolutionDueAt(), t.getFirstRespondedAt(),
                t.getResolvedAt(), t.getSlaStatus(), t.getEscalationLevel(), t.getReopenCount(),
                t.getVersion() == null ? 0 : t.getVersion(), byTicket.getOrDefault(t.getId(), List.of()))).toList();
    }

    static Collection<UUID> idsOf(List<Ticket> ts) { return ts.stream().map(Ticket::getId).toList(); }
}
