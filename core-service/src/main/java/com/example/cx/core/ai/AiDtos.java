package com.example.cx.core.ai;

import com.example.cx.core.tickets.Priority;
import com.example.cx.core.tickets.Visibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request / response records of the AI assist API. */
public final class AiDtos {
    private AiDtos() {}

    public record CategoryRef(UUID id, String name) {}

    public record ArticleRef(UUID id, String title, double score) {}

    /**
     * Contract: {suggestionId, provider, generated, summary, suggestedCategory, suggestedPriority, confidence, explanation,
     * recommendedArticles, suggestedReply, status, degraded, fallbackMessage}. ticketId/createdAt/decidedAt/decisionReason are
     * additive. A degraded answer has no suggestionId/summary/reply (nothing was generated or stored).
     */
    public record SuggestionDto(UUID suggestionId, String provider, boolean generated, String summary, CategoryRef suggestedCategory,
                                Priority suggestedPriority, double confidence, String explanation, List<ArticleRef> recommendedArticles,
                                String suggestedReply, String status, boolean degraded, String fallbackMessage, UUID ticketId,
                                Instant createdAt, Instant decidedAt, String decisionReason) {}

    /** {@code visibility} must be PUBLIC (or omitted): the contract posts the approved reply publicly. */
    public record ApproveRequest(@Size(max = 4000) String editedReply, Visibility visibility) {}

    public record RejectRequest(@NotBlank @Size(max = 500) String reason) {}
}
