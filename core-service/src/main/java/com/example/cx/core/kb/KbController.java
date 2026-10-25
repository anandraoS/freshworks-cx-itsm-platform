package com.example.cx.core.kb;

import com.example.cx.core.common.PageResponse;
import com.example.cx.core.kb.KbDtos.ArticleDto;
import com.example.cx.core.kb.KbDtos.ArticleRequest;
import com.example.cx.core.kb.KbDtos.KbCategoryDto;
import com.example.cx.core.kb.KbDtos.KbCategoryRequest;
import com.example.cx.core.tickets.Visibility;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Knowledge base API (docs/API-CONTRACT.md). Reads: any authenticated user, but a CUSTOMER only ever sees PUBLISHED + PUBLIC
 * articles (enforced in {@link KbService}). Writes, version history and ticket links: AGENT and above.
 */
@RestController
public class KbController {
    private final KbService kb;

    public KbController(KbService kb) { this.kb = kb; }

    @GetMapping("/api/v1/articles")
    public PageResponse<ArticleDto> list(@RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(required = false) UUID categoryId,
                                         @RequestParam(required = false) ArticleStatus status,
                                         @RequestParam(required = false) Visibility visibility) {
        return kb.list(q, categoryId, status, visibility, page, size);
    }

    @GetMapping("/api/v1/articles/{id}")
    public ArticleDto get(@PathVariable UUID id) { return kb.get(id); }

    @PostMapping("/api/v1/articles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('AGENT')")
    public ArticleDto create(@Valid @RequestBody ArticleRequest r) { return kb.create(r); }

    @PutMapping("/api/v1/articles/{id}")
    @PreAuthorize("hasRole('AGENT')")
    public ArticleDto update(@PathVariable UUID id, @Valid @RequestBody ArticleRequest r) { return kb.update(id, r); }

    @PostMapping("/api/v1/articles/{id}/publish")
    @PreAuthorize("hasRole('AGENT')")
    public ArticleDto publish(@PathVariable UUID id) { return kb.publish(id); }

    @GetMapping("/api/v1/articles/{id}/versions")
    @PreAuthorize("hasRole('AGENT')")
    public List<ArticleDto> versions(@PathVariable UUID id) { return kb.versions(id); }

    @PostMapping("/api/v1/articles/{id}/link-ticket/{ticketId}")
    @PreAuthorize("hasRole('AGENT')")
    public ArticleDto linkTicket(@PathVariable UUID id, @PathVariable UUID ticketId) { return kb.linkTicket(id, ticketId); }

    @GetMapping("/api/v1/article-categories")
    public List<KbCategoryDto> categories() { return kb.categories(); }

    @PostMapping("/api/v1/article-categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('AGENT')")
    public KbCategoryDto createCategory(@Valid @RequestBody KbCategoryRequest r) { return kb.createCategory(r.name()); }
}
