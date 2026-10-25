package com.example.cx.core.tickets;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request / response records of the ticket API (entities never leave the service layer). */
public final class TicketDtos {
    private TicketDtos() {}

    public record UserRef(UUID id, String displayName) {}

    public record AttachmentDto(UUID id, String fileName, String contentType, long sizeBytes) {}

    public record TicketDto(UUID id, long number, TicketType type, String title, String description, TicketStatus status,
                            Priority priority, Severity severity, UUID categoryId, String categoryName, List<String> tags,
                            UserRef requester, UserRef assignee, Instant createdAt, Instant updatedAt,
                            Instant firstResponseDueAt, Instant resolutionDueAt, Instant firstRespondedAt, Instant resolvedAt,
                            SlaStatus slaStatus, int escalationLevel, int reopenCount, long version,
                            List<AttachmentDto> attachments) {}

    public record AttachmentRequest(@NotBlank @Size(max = 200) String fileName,
                                    @NotBlank @Size(max = 100) String contentType,
                                    @Min(0) @Max(26_214_400) long sizeBytes) {}

    public record CreateTicketRequest(@NotNull TicketType type,
                                      @NotBlank @Size(max = 200) String title,
                                      @NotBlank @Size(max = 8000) String description,
                                      @NotNull Priority priority,
                                      @NotNull Severity severity,
                                      UUID categoryId,
                                      @Size(max = 20) List<@Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9-]{0,29}$", message = "1-30 chars: letters, digits, hyphen") String> tags,
                                      @Size(max = 10) List<@Valid AttachmentRequest> attachments) {}

    public record UpdateTicketRequest(@NotBlank @Size(max = 200) String title,
                                      @NotBlank @Size(max = 8000) String description,
                                      @NotNull Priority priority,
                                      @NotNull Severity severity,
                                      UUID categoryId,
                                      @Size(max = 20) List<@Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9-]{0,29}$", message = "1-30 chars: letters, digits, hyphen") String> tags,
                                      @NotNull Long version) {}

    public record AssignRequest(UUID assigneeId, @NotNull Long version) {}

    public record TransitionRequest(@NotNull TicketStatus toStatus, @Size(max = 500) String reason, @NotNull Long version) {}

    public record CommentRequest(@NotBlank @Size(max = 8000) String body, @NotNull Visibility visibility) {}

    public record CommentDto(UUID id, UUID authorId, String authorName, String body, Visibility visibility, Instant createdAt,
                             boolean aiGenerated) {}

    public record ActivityDto(UUID id, Instant at, UUID actorId, String actorName, String type, String details) {}

    public record CategoryDto(UUID id, String name, TicketType type, Priority defaultPriority) {}

    public record CategoryRequest(@NotBlank @Size(max = 80) String name, @NotNull TicketType type, @NotNull Priority defaultPriority) {}

    /** Parsed list filters. */
    public record TicketQuery(String q, TicketStatus status, Priority priority, UUID assigneeId, UUID categoryId,
                              TicketType type, SlaStatus slaStatus, boolean mine) {}
}
