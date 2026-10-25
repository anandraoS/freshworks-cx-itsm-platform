package com.example.cx.notification.api;

import com.example.cx.notification.security.AuthPrincipal;
import com.example.cx.notification.security.JwtAuthInterceptor;
import com.example.cx.notification.store.NotificationStore;
import com.example.cx.notification.store.NotificationStore.NotificationRow;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Own-inbox API. Tenant and user come exclusively from the validated JWT (never from request parameters). */
@RestController("cxNotificationInboxController")
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    public record NotificationDto(UUID id, String type, String title, String body, boolean read, OffsetDateTime createdAt, UUID ticketId) {}

    public record Page<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}

    private final NotificationStore store;

    public NotificationController(NotificationStore store) { this.store = store; }

    @GetMapping
    public Page<NotificationDto> list(@RequestParam(name = "unread", defaultValue = "false") boolean unread,
                                      @RequestParam(name = "page", defaultValue = "0") int page,
                                      @RequestParam(name = "size", defaultValue = "20") int size,
                                      HttpServletRequest req) {
        if (page < 0) throw bad("page", "must be >= 0");
        if (size < 1 || size > 100) throw bad("size", "must be between 1 and 100");
        AuthPrincipal p = principal(req);
        List<NotificationDto> content = store.list(p.tenantId(), p.userId(), unread, page, size).stream().map(NotificationController::dto).toList();
        long total = store.count(p.tenantId(), p.userId(), unread);
        return new Page<>(content, page, size, total, (int) ((total + size - 1) / size));
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(HttpServletRequest req) {
        AuthPrincipal p = principal(req);
        return Map.of("count", store.count(p.tenantId(), p.userId(), true));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable("id") UUID id, HttpServletRequest req) {
        AuthPrincipal p = principal(req);
        if (!store.markRead(p.tenantId(), p.userId(), id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Notification not found");
        }
        return ResponseEntity.noContent().build();
    }

    private static NotificationDto dto(NotificationRow r) {
        return new NotificationDto(r.id(), r.type(), r.title(), r.body(), r.read(), r.createdAt(), r.ticketId());
    }

    private static AuthPrincipal principal(HttpServletRequest req) {
        Object p = req.getAttribute(JwtAuthInterceptor.PRINCIPAL);
        if (p instanceof AuthPrincipal ap) return ap;
        throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Not authenticated");
    }

    private static ApiException bad(String field, String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", List.of(new ApiError.FieldError(field, msg)));
    }
}
