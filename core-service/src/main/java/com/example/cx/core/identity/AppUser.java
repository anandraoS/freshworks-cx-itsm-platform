package com.example.cx.core.identity;

import com.example.cx.core.security.Role;
import com.example.cx.core.tenancy.TenantOwned;
import jakarta.persistence.Column;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

/** A user belongs to exactly one tenant (membership = tenant_id + roles). The same e-mail in two tenants = two users. */
@Entity
@Table(name = "app_user")
@Filter(name = TenantOwned.FILTER, condition = TenantOwned.FILTER_CONDITION)
@Getter
@Setter
@NoArgsConstructor
public class AppUser extends TenantOwned {
    @Id
    private UUID id;
    @Column(nullable = false, length = 200)
    private String email;
    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    @Column(name = "roles", nullable = false, length = 100)
    private String rolesCsv = ",";
    @Column(nullable = false)
    private boolean active = true;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    public Set<Role> getRoles() { return Roles.parse(rolesCsv); }
    public void setRoles(Set<Role> r) { this.rolesCsv = Roles.format(r); }

    /** Never include the password hash in logs. */
    @Override
    public String toString() { return "AppUser[" + id + "," + email + "]"; }
}
