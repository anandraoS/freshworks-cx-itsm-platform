package com.example.cx.core.kb;

import com.example.cx.core.tickets.Visibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request / response records of the KB API (entities never leave the service layer). */
public final class KbDtos {
    private KbDtos() {}

    public static final int MAX_BODY = 12_000;

    public record AuthorDto(UUID id, String displayName) {}

    /** Contract: {id,title,slug,body,summary,categoryId,categoryName,visibility,status,version,author,createdAt,updatedAt,relatedTicketIds[]}. */
    public record ArticleDto(UUID id, String title, String slug, String body, String summary, UUID categoryId, String categoryName,
                             Visibility visibility, ArticleStatus status, long version, AuthorDto author, Instant createdAt,
                             Instant updatedAt, List<UUID> relatedTicketIds) {}

    /** {@code version} is optional (the UI does not send it); when present it must equal the current version, else 409. */
    public record ArticleRequest(@NotBlank @Size(min = 3, max = 200) String title,
                                 @Size(max = 500) String summary,
                                 @NotBlank @Size(min = 10, max = MAX_BODY) String body,
                                 UUID categoryId,
                                 @NotNull Visibility visibility,
                                 Long version) {}

    public record KbCategoryDto(UUID id, String name, String slug) {}

    public record KbCategoryRequest(@NotBlank @Size(max = 80) String name) {}
}
