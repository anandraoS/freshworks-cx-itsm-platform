package com.example.cx.core.identity;

import com.example.cx.core.security.Role;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Request/response records for identity endpoints. Entities never leave the service layer. */
public final class IdentityDtos {
    private IdentityDtos() {}

    public record TenantRef(UUID id, String slug, String name) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserDto(UUID id, String email, String displayName, List<Role> roles, boolean active, TenantRef tenant) {}

    public record LoginRequest(@NotBlank @Size(max = 40) String tenantSlug,
                               @NotBlank @Email @Size(max = 200) String email,
                               @NotBlank @Size(max = 200) String password) {
        /** Never print the password. */
        @Override
        public String toString() { return "LoginRequest[tenantSlug=" + tenantSlug + ", email=" + email + ", password=***]"; }
    }

    public record LoginResponse(String accessToken, long expiresInSeconds, UserDto user) {
        @Override
        public String toString() { return "LoginResponse[accessToken=***, user=" + user + "]"; }
    }

    public record RegisterOrganizationRequest(
            @NotBlank @Size(min = 2, max = 120) String organizationName,
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$", message = "3-40 chars: lowercase letters, digits, hyphens") String slug,
            @NotBlank @Email @Size(max = 200) String adminEmail,
            @NotBlank @Size(min = 2, max = 120) String adminDisplayName,
            @NotBlank @Size(min = 8, max = 72, message = "must be 8-72 characters") String password) {
        @Override
        public String toString() { return "RegisterOrganizationRequest[slug=" + slug + ", adminEmail=" + adminEmail + ", password=***]"; }
    }

    public record CreateUserRequest(@NotBlank @Email @Size(max = 200) String email,
                                    @NotBlank @Size(min = 2, max = 120) String displayName,
                                    @NotEmpty Set<Role> roles,
                                    @NotBlank @Size(min = 8, max = 72, message = "must be 8-72 characters") String password) {
        @Override
        public String toString() { return "CreateUserRequest[email=" + email + ", roles=" + roles + ", password=***]"; }
    }

    public record UpdateUserRequest(@NotBlank @Size(min = 2, max = 120) String displayName,
                                    @NotEmpty Set<Role> roles, @NotNull Boolean active) {}

    public record IntegrationsDto(@Size(max = 40) String emailProvider,
                                  @Pattern(regexp = "^$|^https://[^\\s]{1,280}$", message = "must be an https URL") String slackWebhookUrl,
                                  @Size(max = 40) String aiProvider) {
        @Override
        public String toString() { return "IntegrationsDto[emailProvider=" + emailProvider + ", slackWebhookUrl=***, aiProvider=" + aiProvider + "]"; }
    }

    public record TenantSettingsDto(@NotBlank @Size(max = 120) String name,
                                    @NotBlank @Size(max = 60) String timezone,
                                    @NotBlank @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "HH:mm") String businessHoursStart,
                                    @NotBlank @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "HH:mm") String businessHoursEnd,
                                    @NotEmpty List<@Pattern(regexp = "^(MON|TUE|WED|THU|FRI|SAT|SUN)$") String> businessDays,
                                    @NotBlank @Size(max = 10) String defaultLanguage,
                                    @Valid @NotNull IntegrationsDto integrations) {}
}
