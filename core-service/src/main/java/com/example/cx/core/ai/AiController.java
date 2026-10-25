package com.example.cx.core.ai;

import com.example.cx.core.ai.AiDtos.ApproveRequest;
import com.example.cx.core.ai.AiDtos.RejectRequest;
import com.example.cx.core.ai.AiDtos.SuggestionDto;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** AI assist API (docs/API-CONTRACT.md). AGENT and above only; CUSTOMER gets 403; other-tenant ids get 404. */
@RestController
@PreAuthorize("hasRole('AGENT')")
public class AiController {
    private final AiAssistService assist;
    private final AiDecisionService decisions;

    public AiController(AiAssistService assist, AiDecisionService decisions) {
        this.assist = assist;
        this.decisions = decisions;
    }

    @PostMapping("/api/v1/tickets/{id}/ai/analyze")
    public SuggestionDto analyze(@PathVariable UUID id) { return assist.analyze(id); }

    @GetMapping("/api/v1/tickets/{id}/ai/suggestions")
    public List<SuggestionDto> suggestions(@PathVariable UUID id) { return assist.list(id); }

    @PostMapping("/api/v1/ai/suggestions/{id}/approve")
    public SuggestionDto approve(@PathVariable UUID id, @Valid @RequestBody(required = false) ApproveRequest r) { return decisions.approve(id, r); }

    @PostMapping("/api/v1/ai/suggestions/{id}/reject")
    public SuggestionDto reject(@PathVariable UUID id, @Valid @RequestBody RejectRequest r) { return decisions.reject(id, r); }
}
