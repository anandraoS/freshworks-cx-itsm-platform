package com.example.cx.core.ai;

import com.example.cx.core.ai.AiDtos.ApproveRequest;
import com.example.cx.core.ai.AiDtos.RejectRequest;
import com.example.cx.core.ai.AiDtos.SuggestionDto;
import com.example.cx.core.audit.AuditService;
import com.example.cx.core.cache.CacheService;
import com.example.cx.core.common.TxHooks;
import com.example.cx.core.common.ApiException;
import com.example.cx.core.identity.AppUser;
import com.example.cx.core.identity.UserRepository;
import com.example.cx.core.security.AuthUser;
import com.example.cx.core.security.CurrentUser;
import com.example.cx.core.tenancy.TenantContext;
import com.example.cx.core.tickets.Ticket;
import com.example.cx.core.tickets.TicketDtos.CommentDto;
import com.example.cx.core.tickets.TicketEvents;
import com.example.cx.core.tickets.TicketRepository;
import com.example.cx.core.tickets.TicketService;
import com.example.cx.core.tickets.Visibility;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * HUMAN APPROVAL GATE. The only code path that can turn a suggestion into customer-visible text.
 *  - approve: atomically flips PENDING_REVIEW -> APPROVED | EDITED_APPROVED (compare-and-set; a second approve/reject gets 409),
 *    then posts exactly ONE comment through {@link TicketService#addComment} (PUBLIC, aiGenerated=true, authored by the approving agent);
 *  - reject: flips to REJECTED with a reason and posts nothing.
 * Both run in one transaction with the audit row and the {@code ai.suggestion.decided} outbox event, so either everything commits or
 * nothing does (a failed comment rolls the status flip back).
 */
@Service
public class AiDecisionService {
    private final AiSuggestionRepository repo;
    private final TicketRepository tickets;
    private final TicketService ticketService;
    private final TicketEvents events;
    private final UserRepository users;
    private final AuditService audit;
    private final AiAssistService assist;
    private final CacheService cache;

    public AiDecisionService(AiSuggestionRepository repo, TicketRepository tickets, TicketService ticketService, TicketEvents events,
                             UserRepository users, AuditService audit, AiAssistService assist, CacheService cache) {
        this.cache = cache;
        this.repo = repo;
        this.tickets = tickets;
        this.ticketService = ticketService;
        this.events = events;
        this.users = users;
        this.audit = audit;
        this.assist = assist;
    }

    @Transactional
    public SuggestionDto approve(UUID id, ApproveRequest req) {
        AuthUser me = CurrentUser.require();
        UUID tenant = TenantContext.require();
        if (req != null && req.visibility() != null && req.visibility() != Visibility.PUBLIC)
            throw ApiException.validation("visibility", "AI replies are posted as PUBLIC only");
        AiSuggestion s = repo.findByIdAndTenantId(id, tenant).orElseThrow(() -> ApiException.notFound("Suggestion"));
        Ticket t = tickets.findByIdAndTenantId(s.getTicketId(), tenant).orElseThrow(() -> ApiException.notFound("Ticket"));
        if (s.getStatus() != SuggestionStatus.PENDING_REVIEW) throw ApiException.conflict("Suggestion has already been decided");

        String edited = req == null || req.editedReply() == null || req.editedReply().isBlank() ? null : req.editedReply().trim();
        String original = s.getSuggestedReply() == null ? null : s.getSuggestedReply().trim();
        String finalText = edited != null ? edited : original;
        if (finalText == null || finalText.isBlank()) throw ApiException.validation("editedReply", "this suggestion has no reply; provide editedReply");
        SuggestionStatus to = edited != null && !edited.equals(original) ? SuggestionStatus.EDITED_APPROVED : SuggestionStatus.APPROVED;

        if (repo.decide(id, tenant, to, me.userId(), Instant.now(), null) != 1) throw ApiException.conflict("Suggestion has already been decided");
        CommentDto c = ticketService.addComment(t.getId(), finalText, Visibility.PUBLIC, true);   // exactly one comment
        repo.attachComment(id, tenant, c.id());

        audit.record("AI_SUGGESTION_" + to.name(), "AI_SUGGESTION", id, Map.of("ticketId", t.getId().toString(), "commentId", c.id().toString(),
                "edited", to == SuggestionStatus.EDITED_APPROVED, "provider", s.getProvider()));
        publish(t, s, me, to.name(), to == SuggestionStatus.EDITED_APPROVED);
        return assist.toDto(repo.findByIdAndTenantId(id, tenant).orElseThrow());
    }

    @Transactional
    public SuggestionDto reject(UUID id, RejectRequest req) {
        AuthUser me = CurrentUser.require();
        UUID tenant = TenantContext.require();
        AiSuggestion s = repo.findByIdAndTenantId(id, tenant).orElseThrow(() -> ApiException.notFound("Suggestion"));
        Ticket t = tickets.findByIdAndTenantId(s.getTicketId(), tenant).orElseThrow(() -> ApiException.notFound("Ticket"));
        if (s.getStatus() != SuggestionStatus.PENDING_REVIEW) throw ApiException.conflict("Suggestion has already been decided");
        String reason = req.reason().trim();
        if (repo.decide(id, tenant, SuggestionStatus.REJECTED, me.userId(), Instant.now(), reason) != 1)
            throw ApiException.conflict("Suggestion has already been decided");
        audit.record("AI_SUGGESTION_REJECTED", "AI_SUGGESTION", id, Map.of("ticketId", t.getId().toString(), "reason", reason, "provider", s.getProvider()));
        publish(t, s, me, SuggestionStatus.REJECTED.name(), false);
        return assist.toDto(repo.findByIdAndTenantId(id, tenant).orElseThrow());
    }

    /** ai.suggestion.decided: the assignee (if not the decider) is told; payload carries ids and the decision only, no ticket text. */
    private void publish(Ticket t, AiSuggestion s, AuthUser me, String decision, boolean edited) {
        String actorName = users.findByIdAndTenantId(me.userId(), t.getTenantId()).map(AppUser::getDisplayName).orElse("Agent");
        Set<UUID> recipients = t.getAssigneeId() == null ? Set.of() : Set.of(t.getAssigneeId());
        Map<String, String> payload = events.base(t, me.userId(), actorName, recipients, me.userId());
        payload.put("suggestionId", s.getId().toString());
        payload.put("decision", decision);
        payload.put("edited", String.valueOf(edited));
        payload.put("provider", s.getProvider());
        events.publish(TicketEvents.AI_DECIDED, payload);
        // the dashboard KPI aiAcceptanceRatePercent changes with every decision: drop the cached dashboard after commit
        UUID tenant = t.getTenantId();
        TxHooks.afterCommit(() -> cache.evictByPrefix(tenant, "analytics:"));
    }
}
