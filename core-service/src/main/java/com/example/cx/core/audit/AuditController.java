package com.example.cx.core.audit;

import com.example.cx.core.common.PageResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditController {
    private final AuditService audit;

    public AuditController(AuditService audit) { this.audit = audit; }

    public record AuditEventDto(UUID id, Instant at, UUID actorId, String actorEmail, String action, String entityType,
                                String entityId, String details) {}

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<AuditEventDto> list(@RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size,
                                            @RequestParam(required = false) String entityType,
                                            @RequestParam(required = false) UUID actorId) {
        return PageResponse.of(audit.search(entityType, actorId, page, size), e -> new AuditEventDto(
                e.getId(), e.getOccurredAt(), e.getActorId(), e.getActorEmail(), e.getAction(), e.getEntityType(),
                e.getEntityId(), e.getDetails()));
    }
}
