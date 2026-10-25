package com.example.cx.core.auth;

import com.example.cx.core.audit.AuditService;
import com.example.cx.core.common.ApiException;
import com.example.cx.core.common.ErrorCode;
import com.example.cx.core.identity.AppUser;
import com.example.cx.core.identity.IdentityDtos.LoginRequest;
import com.example.cx.core.identity.IdentityDtos.LoginResponse;
import com.example.cx.core.identity.IdentityDtos.RegisterOrganizationRequest;
import com.example.cx.core.identity.IdentityDtos.TenantRef;
import com.example.cx.core.identity.IdentityDtos.UserDto;
import com.example.cx.core.identity.Organization;
import com.example.cx.core.identity.OrganizationRepository;
import com.example.cx.core.identity.UserRepository;
import com.example.cx.core.identity.UserService;
import com.example.cx.core.security.AuthUser;
import com.example.cx.core.security.CurrentUser;
import com.example.cx.core.security.JwtService;
import com.example.cx.core.security.LoginRateLimiter;
import com.example.cx.core.security.Role;
import com.example.cx.core.security.TokenRevocationService;
import com.example.cx.core.tenancy.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Authentication use-cases. Tenant resolution here is SERVER-SIDE (slug -> organization), which is the one legitimate place a
 * client names a tenant: it only selects whose credentials to check; the tenant id used afterwards comes from the issued
 * (signed) token.
 */
@Service
public class AuthService {
    private final OrganizationRepository orgs;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final TokenRevocationService revocations;
    private final LoginRateLimiter limiter;
    private final AuditService audit;
    private final TenantProvisioner provisioner;
    private final MeterRegistry meters;
    private final TransactionTemplate tx;
    /** Verified against when the tenant or user does not exist, so response time does not reveal which one was wrong. */
    private final String dummyHash;

    public AuthService(OrganizationRepository orgs, UserRepository users, PasswordEncoder encoder, JwtService jwt,
                       TokenRevocationService revocations, LoginRateLimiter limiter, AuditService audit,
                       TenantProvisioner provisioner, MeterRegistry meters, PlatformTransactionManager txm) {
        this.orgs = orgs;
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.revocations = revocations;
        this.limiter = limiter;
        this.audit = audit;
        this.provisioner = provisioner;
        this.meters = meters;
        this.tx = new TransactionTemplate(txm);
        this.dummyHash = encoder.encode("dummy-" + UUID.randomUUID());
    }

    public LoginResponse login(LoginRequest r, String clientIp) {
        String slug = r.tenantSlug().trim().toLowerCase(Locale.ROOT);
        String email = r.email().trim().toLowerCase(Locale.ROOT);
        limiter.check("ip:" + clientIp, "acct:" + slug + ":" + email);

        Organization org = orgs.findBySlug(slug).orElse(null);
        AppUser user = org == null ? null
                : TenantContext.callAs(org.getId(), () -> users.findByTenantIdAndEmail(org.getId(), email)).orElse(null);
        boolean passwordOk = encoder.matches(r.password(), user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !user.isActive() || !passwordOk) {
            meters.counter("cx.auth.login", "result", "failure").increment();
            if (org != null) {
                TenantContext.runAs(org.getId(), () -> audit.recordAs(org.getId(), user == null ? null : user.getId(), email,
                        "LOGIN_FAILED", "USER", user == null ? null : user.getId(), Map.of("reason", "invalid credentials")));
            }
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid credentials");
        }
        meters.counter("cx.auth.login", "result", "success").increment();
        TenantContext.runAs(org.getId(), () -> {
            tx.executeWithoutResult(s -> users.touchLogin(user.getId(), org.getId(), Instant.now()));
            audit.recordAs(org.getId(), user.getId(), user.getEmail(), "LOGIN_SUCCESS", "USER", user.getId(), null);
        });
        return issue(user, org);
    }

    public LoginResponse register(RegisterOrganizationRequest r, String clientIp) {
        limiter.check("reg:" + clientIp);
        String slug = r.slug().trim().toLowerCase(Locale.ROOT);
        if (orgs.existsBySlug(slug)) throw ApiException.conflict("Organization slug is already taken");
        Organization org;
        try {
            org = provisioner.createOrganization(r.organizationName().trim(), slug, null, null, null, null);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("Organization slug is already taken"); // lost a race on the unique slug
        }
        provisioner.provisionDefaults(org.getId());
        AppUser admin = provisioner.createUser(org.getId(), r.adminEmail().trim(), r.adminDisplayName().trim(),
                encoder.encode(r.password()), Set.of(Role.ADMIN));
        TenantContext.runAs(org.getId(), () -> audit.recordAs(org.getId(), admin.getId(), admin.getEmail(),
                "TENANT_REGISTERED", "TENANT", org.getId(), Map.of("slug", slug)));
        return issue(admin, org);
    }

    /** Current user from the DB (not just the token claims). */
    public UserDto me() {
        AuthUser me = CurrentUser.require();
        Organization org = orgs.findById(me.tenantId()).orElseThrow(() -> ApiException.notFound("Tenant"));
        AppUser u = users.findByIdAndTenantId(me.userId(), me.tenantId()).filter(AppUser::isActive)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Account is not active"));
        return UserService.toDto(u, new TenantRef(org.getId(), org.getSlug(), org.getName()));
    }

    public void logout() {
        AuthUser me = CurrentUser.require();
        revocations.revoke(me.jti(), me.expiresAt());
        audit.record("LOGOUT", "USER", me.userId(), null);
    }

    private LoginResponse issue(AppUser u, Organization org) {
        JwtService.Issued t = jwt.issue(u.getId(), org.getId(), u.getEmail(), u.getRoles());
        return new LoginResponse(t.token(), jwt.ttlSeconds(),
                UserService.toDto(u, new TenantRef(org.getId(), org.getSlug(), org.getName())));
    }

    static Optional<String> firstForwarded(String header) {
        if (header == null || header.isBlank()) return Optional.empty();
        String first = header.split(",")[0].trim();
        return first.length() <= 45 ? Optional.of(first) : Optional.empty();
    }
}
