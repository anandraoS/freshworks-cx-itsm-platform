package com.example.cx.core.auth;

import com.example.cx.core.identity.AppUser;
import com.example.cx.core.identity.Organization;
import com.example.cx.core.identity.OrganizationRepository;
import com.example.cx.core.identity.UserRepository;
import com.example.cx.core.security.Role;
import com.example.cx.core.sla.SlaPolicy;
import com.example.cx.core.sla.SlaPolicyRepository;
import com.example.cx.core.tenancy.TenantContext;
import com.example.cx.core.tickets.Priority;
import com.example.cx.core.tickets.TicketCategory;
import com.example.cx.core.tickets.TicketCategoryRepository;
import com.example.cx.core.tickets.TicketCounter;
import com.example.cx.core.tickets.TicketCounterRepository;
import com.example.cx.core.tickets.TicketType;
import com.example.cx.core.workflow.WorkflowModel.Action;
import com.example.cx.core.workflow.WorkflowModel.Condition;
import com.example.cx.core.workflow.WorkflowModel.Rule;
import com.example.cx.core.workflow.WorkflowService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates a tenant and its defaults (counter, categories, SLA policies, default ACTIVE workflow v1). Used by
 * register-organization and by the demo seeder. A SYSTEM component: it binds the TenantContext itself for the NEW tenant id it
 * just generated (never from client input).
 */
@Service
public class TenantProvisioner {
    private final OrganizationRepository orgs;
    private final UserRepository users;
    private final TicketCounterRepository counters;
    private final TicketCategoryRepository categories;
    private final SlaPolicyRepository policies;
    private final WorkflowService workflows;
    private final TransactionTemplate tx;

    public TenantProvisioner(OrganizationRepository orgs, UserRepository users, TicketCounterRepository counters,
                             TicketCategoryRepository categories, SlaPolicyRepository policies, WorkflowService workflows,
                             PlatformTransactionManager txm) {
        this.orgs = orgs;
        this.users = users;
        this.counters = counters;
        this.categories = categories;
        this.policies = policies;
        this.workflows = workflows;
        this.tx = new TransactionTemplate(txm);
    }

    public Organization createOrganization(String name, String slug, String timezone, String hoursStart, String hoursEnd, String days) {
        Organization o = new Organization();
        o.setId(UUID.randomUUID());
        o.setName(name);
        o.setSlug(slug);
        if (timezone != null) o.setTimezone(timezone);
        if (hoursStart != null) o.setBusinessHoursStart(hoursStart);
        if (hoursEnd != null) o.setBusinessHoursEnd(hoursEnd);
        if (days != null) o.setBusinessDays(days);
        o.setCreatedAt(Instant.now());
        return tx.execute(s -> orgs.saveAndFlush(o));
    }

    /** Runs inside the tenant context of {@code tenantId}. */
    public void provisionDefaults(UUID tenantId) {
        TenantContext.runAs(tenantId, () -> {
            tx.executeWithoutResult(s -> {
                counters.save(new TicketCounter(tenantId));
                category(tenantId, "Billing", TicketType.SUPPORT, Priority.MEDIUM);
                category(tenantId, "Product Question", TicketType.SUPPORT, Priority.LOW);
                category(tenantId, "Bug Report", TicketType.SUPPORT, Priority.HIGH);
                category(tenantId, "Account Access", TicketType.SUPPORT, Priority.MEDIUM);
                category(tenantId, "Incident", TicketType.IT_SERVICE, Priority.HIGH);
                category(tenantId, "Service Request", TicketType.IT_SERVICE, Priority.MEDIUM);
                category(tenantId, "Hardware", TicketType.IT_SERVICE, Priority.MEDIUM);
                category(tenantId, "Access Request", TicketType.IT_SERVICE, Priority.LOW);
                // business minutes
                policy(tenantId, Priority.URGENT, 30, 240, List.of(50, 75, 100));
                policy(tenantId, Priority.HIGH, 60, 480, List.of(75, 100));
                policy(tenantId, Priority.MEDIUM, 240, 1440, List.of(75, 100));
                policy(tenantId, Priority.LOW, 480, 2880, List.of(75, 100));
            });
            workflows.createActive(defaultWorkflow());
        });
    }

    public AppUser createUser(UUID tenantId, String email, String displayName, String passwordHash, Set<Role> roles) {
        AppUser u = new AppUser();
        u.setId(UUID.randomUUID());
        u.setTenantId(tenantId);
        u.setEmail(email.toLowerCase());
        u.setDisplayName(displayName);
        u.setPasswordHash(passwordHash);
        u.setRoles(roles);
        u.setCreatedAt(Instant.now());
        return TenantContext.callAs(tenantId, () -> tx.execute(s -> users.saveAndFlush(u)));
    }

    static WorkflowService.WorkflowRequest defaultWorkflow() {
        Rule security = new Rule("Security keyword raises priority",
                List.of(new Condition("title", "CONTAINS", "security")),
                List.of(new Action("SET_PRIORITY", Map.of("priority", "HIGH")), new Action("ADD_TAG", Map.of("tag", "security"))));
        Rule urgent = new Rule("Urgent tickets alert managers",
                List.of(new Condition("priority", "EQ", "URGENT")),
                List.of(new Action("ADD_TAG", Map.of("tag", "urgent")),
                        new Action("NOTIFY", Map.of("role", "MANAGER", "message", "Urgent ticket needs attention"))));
        Rule route = new Rule("Route high priority to least loaded agent",
                List.of(new Condition("priority", "IN", List.of("HIGH", "URGENT"))),
                List.of(new Action("ASSIGN_TO_ROLE_LEAST_LOADED", Map.of("role", "AGENT"))));
        return new WorkflowService.WorkflowRequest("Default workflow", List.of(security, urgent, route), List.of());
    }

    private void category(UUID tenantId, String name, TicketType type, Priority p) {
        TicketCategory c = new TicketCategory();
        c.setId(UUID.randomUUID());
        c.setTenantId(tenantId);
        c.setName(name);
        c.setType(type);
        c.setDefaultPriority(p);
        categories.save(c);
    }

    private void policy(UUID tenantId, Priority p, int first, int resolution, List<Integer> pct) {
        SlaPolicy sp = new SlaPolicy();
        sp.setId(UUID.randomUUID());
        sp.setTenantId(tenantId);
        sp.setPriority(p);
        sp.setFirstResponseMinutes(first);
        sp.setResolutionMinutes(resolution);
        sp.setEscalationAfterPercent(pct);
        sp.setUpdatedAt(Instant.now());
        policies.save(sp);
    }
}
