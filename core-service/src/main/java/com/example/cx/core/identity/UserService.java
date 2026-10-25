package com.example.cx.core.identity;

import com.example.cx.core.audit.AuditService;
import com.example.cx.core.common.ApiException;
import com.example.cx.core.identity.IdentityDtos.*;
import com.example.cx.core.security.Role;
import com.example.cx.core.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public UserService(UserRepository users, PasswordEncoder encoder, AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
    }

    public static UserDto toDto(AppUser u, TenantRef tenant) {
        List<Role> roles = new ArrayList<>(u.getRoles());
        return new UserDto(u.getId(), u.getEmail(), u.getDisplayName(), roles, u.isActive(), tenant);
    }

    @Transactional(readOnly = true)
    public List<UserDto> list(String q, boolean includeInactive, int limit) {
        UUID t = TenantContext.require();
        String like = (q == null || q.isBlank()) ? "%" : "%" + escapeLike(q.toLowerCase(Locale.ROOT)) + "%";
        int bounded = Math.max(1, Math.min(limit, 500)); // unbounded queries are forbidden
        return users.search(t, !includeInactive, like, PageRequest.of(0, bounded)).stream().map(u -> toDto(u, null)).toList();
    }

    @Transactional(readOnly = true)
    public AppUser require(UUID id) {
        return users.findByIdAndTenantId(id, TenantContext.require()).orElseThrow(() -> ApiException.notFound("User"));
    }

    @Transactional
    public UserDto create(CreateUserRequest r) {
        UUID t = TenantContext.require();
        String email = r.email().trim().toLowerCase(Locale.ROOT);
        if (users.findByTenantIdAndEmail(t, email).isPresent()) throw ApiException.conflict("A user with this e-mail already exists");
        AppUser u = new AppUser();
        u.setId(UUID.randomUUID());
        u.setTenantId(t);
        u.setEmail(email);
        u.setDisplayName(r.displayName().trim());
        u.setPasswordHash(encoder.encode(r.password()));
        u.setRoles(r.roles());
        users.save(u);
        audit.record("USER_CREATED", "USER", u.getId(), Map.of("email", email, "roles", r.roles().toString()));
        return toDto(u, null);
    }

    @Transactional
    public UserDto update(UUID id, UpdateUserRequest r) {
        UUID t = TenantContext.require();
        AppUser u = users.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("User"));
        boolean wasAdmin = u.isActive() && u.getRoles().contains(Role.ADMIN);
        boolean stillAdmin = r.active() && r.roles().contains(Role.ADMIN);
        if (wasAdmin && !stillAdmin && users.countActiveAdmins(t) <= 1)
            throw ApiException.conflict("Cannot remove the last active administrator");
        u.setDisplayName(r.displayName().trim());
        u.setRoles(r.roles());
        u.setActive(r.active());
        users.save(u);
        audit.record("USER_UPDATED", "USER", u.getId(), Map.of("roles", r.roles().toString(), "active", r.active()));
        return toDto(u, null);
    }

    static String escapeLike(String s) { return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }
}
