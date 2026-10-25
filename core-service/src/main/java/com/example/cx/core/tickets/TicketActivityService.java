package com.example.cx.core.tickets;

import com.example.cx.core.tenancy.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes the append-only per-ticket history. Joins the caller transaction. */
@Service
public class TicketActivityService {
    private final TicketActivityRepository repo;

    public TicketActivityService(TicketActivityRepository repo) { this.repo = repo; }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(UUID ticketId, String type, String details, UUID actorId, String actorName) {
        TicketActivity a = new TicketActivity();
        a.setId(UUID.randomUUID());
        a.setTenantId(TenantContext.require());
        a.setTicketId(ticketId);
        a.setActorId(actorId);
        a.setActorName(actorName);
        a.setType(type);
        a.setDetails(details == null ? null : details.length() > 990 ? details.substring(0, 990) : details);
        a.setCreatedAt(Instant.now());
        repo.save(a);
    }
}
