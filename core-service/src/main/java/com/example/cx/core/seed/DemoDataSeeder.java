package com.example.cx.core.seed;

import com.example.cx.core.auth.TenantProvisioner;
import com.example.cx.core.identity.AppUser;
import com.example.cx.core.identity.Organization;
import com.example.cx.core.identity.OrganizationRepository;
import com.example.cx.core.identity.TenantSettingsService;
import com.example.cx.core.kb.KbDemoSeeder;
import com.example.cx.core.security.Role;
import com.example.cx.core.sla.SlaCalculator;
import com.example.cx.core.sla.SlaService;
import com.example.cx.core.tenancy.TenantContext;
import com.example.cx.core.tickets.Priority;
import com.example.cx.core.tickets.Severity;
import com.example.cx.core.tickets.SlaStatus;
import com.example.cx.core.tickets.Ticket;
import com.example.cx.core.tickets.TicketActivity;
import com.example.cx.core.tickets.TicketAttachment;
import com.example.cx.core.tickets.TicketCategory;
import com.example.cx.core.tickets.TicketComment;
import com.example.cx.core.tickets.TicketCounter;
import com.example.cx.core.tickets.TicketStatus;
import com.example.cx.core.tickets.TicketType;
import com.example.cx.core.tickets.Visibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deterministic synthetic demo data (fixed RNG seeds per tenant; only the base "now" moves). Idempotent: a tenant whose slug
 * already exists is skipped. Everything is SYNTHETIC: names, e-mails (.test TLD), texts. All demo accounts share the documented
 * dummy password and are created with a BCrypt hash computed here at seed time - the password is never stored in clear text.
 * Seeding writes rows directly (no outbox events), so it never floods the notification pipeline.
 */
@Component
@Order(100)
@ConditionalOnProperty(name = "cx.seed.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    public static final String DEMO_PASSWORD = "Passw0rd!demo"; // documented, synthetic, demo-only

    record UserSpec(String email, String name, Set<Role> roles) {}

    record TenantSpec(String slug, String name, String timezone, String hoursStart, String hoursEnd, String days,
                      List<UserSpec> users, int tickets, long seed, double itShare) {}

    private static final List<TenantSpec> TENANTS = List.of(
            new TenantSpec("acme-retail", "Acme Retail", "America/New_York", "09:00", "17:00", "MON,TUE,WED,THU,FRI", List.of(
                    new UserSpec("admin@acme.test", "Alice Admin", EnumSet.of(Role.ADMIN)),
                    new UserSpec("manager@acme.test", "Marcus Manager", EnumSet.of(Role.MANAGER)),
                    new UserSpec("agent1@acme.test", "Ava Agent", EnumSet.of(Role.AGENT)),
                    new UserSpec("agent2@acme.test", "Ben Agent", EnumSet.of(Role.AGENT)),
                    new UserSpec("customer1@acme.test", "Casey Customer", EnumSet.of(Role.CUSTOMER))), 150, 20240101L, 0.15),
            new TenantSpec("globex-it", "Globex IT Services", "Europe/London", "08:00", "18:00", "MON,TUE,WED,THU,FRI", List.of(
                    new UserSpec("admin@globex.test", "Gina Admin", EnumSet.of(Role.ADMIN)),
                    new UserSpec("agent1@globex.test", "Gus Agent", EnumSet.of(Role.AGENT)),
                    new UserSpec("customer1@globex.test", "Cleo Customer", EnumSet.of(Role.CUSTOMER))), 100, 20240202L, 0.75),
            new TenantSpec("initech-health", "Initech Health", "America/Chicago", "07:00", "19:00", "MON,TUE,WED,THU,FRI,SAT", List.of(
                    new UserSpec("admin@initech.test", "Ian Admin", EnumSet.of(Role.ADMIN)),
                    new UserSpec("agent1@initech.test", "Ivy Agent", EnumSet.of(Role.AGENT))), 60, 20240303L, 0.5));

    private static final String[] SUPPORT_TITLES = {
            "Cannot complete checkout with saved card", "Refund not received after return", "Order status stuck on processing",
            "Wrong item delivered", "Invoice shows incorrect VAT", "Unable to reset password", "Promo code rejected at payment",
            "Mobile app crashes on startup", "Loyalty points missing after purchase", "Shipping address cannot be updated",
            "Duplicate charge on statement", "Product page shows wrong price", "Gift card balance not applied",
            "Subscription renewed after cancellation", "Security concern: unknown login alert"};
    private static final String[] IT_TITLES = {
            "VPN disconnects every few minutes", "Laptop battery drains within an hour", "Request access to finance shared drive",
            "Printer on floor 3 offline", "Email sync failing on phone", "New starter hardware request", "Software licence expired",
            "Wi-Fi slow in meeting room B", "Cannot join video calls", "Disk space low on build server",
            "MFA token lost", "Security patch required for workstation", "Shared mailbox permissions", "Monitor flickering", "Password expired"};
    private static final String[] BODIES = {
            "Started happening yesterday and affects my daily work. Steps to reproduce are in the attached notes.",
            "This is blocking me; please advise on a workaround or an ETA.",
            "Happens intermittently, mostly in the morning. No recent changes on my side.",
            "Several colleagues report the same behaviour. Reference screenshots attached (synthetic).",
            "First time I see this. Let me know what extra information you need."};
    private static final String[] AGENT_REPLIES = {
            "Thanks for reaching out, I am looking into this now.", "Could you share a screenshot and the exact time it happened?",
            "I have escalated this to the relevant team and will update you shortly.",
            "A fix has been applied, please confirm it works for you.", "Applying a workaround for now while we investigate the root cause."};
    private static final String[] CUSTOMER_REPLIES = {
            "Thank you, that helps.", "I tried that and the problem is still there.", "Here is the extra information you asked for.",
            "Confirmed, it works now."};
    private static final String[] INTERNAL_NOTES = {
            "Checked the logs, matches known issue. Linking to existing problem record.", "Customer is a priority account, handle with care.",
            "Waiting for vendor confirmation before replying."};
    private static final String[] TAG_POOL = {"vip", "billing", "mobile", "network", "regression", "followup", "hardware"};

    private final OrganizationRepository orgs;
    private final TenantProvisioner provisioner;
    private final PasswordEncoder encoder;
    private final SlaService sla;
    private final TenantSettingsService settings;
    private final TransactionTemplate tx;
    private final KbDemoSeeder kbSeeder;
    @PersistenceContext
    private EntityManager em;

    public DemoDataSeeder(OrganizationRepository orgs, TenantProvisioner provisioner, PasswordEncoder encoder, SlaService sla,
                          TenantSettingsService settings, PlatformTransactionManager txm, KbDemoSeeder kbSeeder) {
        this.kbSeeder = kbSeeder;
        this.orgs = orgs;
        this.provisioner = provisioner;
        this.encoder = encoder;
        this.sla = sla;
        this.settings = settings;
        this.tx = new TransactionTemplate(txm);
    }

    @Override
    public void run(ApplicationArguments args) {
        for (TenantSpec spec : TENANTS) {
            if (orgs.existsBySlug(spec.slug())) {
                log.info("Seed: tenant {} already exists, skipping", spec.slug());
                continue;
            }
            long t0 = System.currentTimeMillis();
            seedTenant(spec);
            log.info("Seed: tenant {} created with {} tickets in {} ms", spec.slug(), spec.tickets(), System.currentTimeMillis() - t0);
        }
    }

    void seedTenant(TenantSpec spec) {
        Organization org = provisioner.createOrganization(spec.name(), spec.slug(), spec.timezone(), spec.hoursStart(), spec.hoursEnd(), spec.days());
        UUID tenant = org.getId();
        provisioner.provisionDefaults(tenant);
        Random rnd = new Random(spec.seed());
        Instant now = Instant.now();
        List<AppUser> users = new ArrayList<>();
        for (UserSpec u : spec.users()) {
            AppUser created = provisioner.createUser(tenant, u.email(), u.name(), encoder.encode(DEMO_PASSWORD), u.roles());
            users.add(created);
        }
        TenantContext.runAs(tenant, () -> tx.executeWithoutResult(s -> {
            for (AppUser u : users) {
                if (rnd.nextDouble() < 0.75) // recent logins feed the "active users" metric
                    em.createQuery("update AppUser u set u.lastLoginAt = :at where u.id = :id")
                            .setParameter("at", now.minus(rnd.nextInt(6 * 24 * 60), ChronoUnit.MINUTES)).setParameter("id", u.getId()).executeUpdate();
            }
            seedTickets(spec, tenant, users, rnd, now);
            kbSeeder.seed(tenant, users.stream().filter(u -> u.getRoles().contains(Role.ADMIN)).findFirst().orElse(users.get(0)).getId());
        }));
    }

    private void seedTickets(TenantSpec spec, UUID tenant, List<AppUser> users, Random rnd, Instant now) {
        List<TicketCategory> cats = em.createQuery("select c from TicketCategory c where c.tenantId = :t", TicketCategory.class)
                .setParameter("t", tenant).getResultList();
        List<AppUser> staff = users.stream().filter(u -> u.getRoles().stream().anyMatch(Role::isStaff)).toList();
        List<AppUser> agents = users.stream().filter(u -> u.getRoles().contains(Role.AGENT) || u.getRoles().contains(Role.MANAGER)).toList();
        List<AppUser> customers = users.stream().filter(u -> u.getRoles().equals(EnumSet.of(Role.CUSTOMER))).toList();

        List<Long> ages = new ArrayList<>(); // minutes before now, oldest first -> numbers ascend with time
        for (int i = 0; i < spec.tickets(); i++) ages.add(30L + (long) (rnd.nextDouble() * (45L * 24 * 60 - 30)));
        ages.sort(Comparator.reverseOrder());

        for (int i = 0; i < ages.size(); i++) {
            Instant created = now.minus(ages.get(i), ChronoUnit.MINUTES);
            long ageHours = ages.get(i) / 60;
            TicketType type = rnd.nextDouble() < spec.itShare() ? TicketType.IT_SERVICE : TicketType.SUPPORT;
            Priority prio = pick(rnd, new Priority[]{Priority.LOW, Priority.MEDIUM, Priority.HIGH, Priority.URGENT}, new int[]{25, 45, 22, 8});
            Severity sev = prio == Priority.URGENT ? Severity.CRITICAL : prio == Priority.HIGH ? pick(rnd, Severity.values(), new int[]{20, 60, 20})
                    : pick(rnd, Severity.values(), new int[]{70, 25, 5});
            List<TicketCategory> typed = cats.stream().filter(c -> c.getType() == type).toList();
            TicketCategory cat = rnd.nextDouble() < 0.9 && !typed.isEmpty() ? typed.get(rnd.nextInt(typed.size())) : null;
            AppUser requester = type == TicketType.SUPPORT && !customers.isEmpty() ? customers.get(rnd.nextInt(customers.size()))
                    : (customers.isEmpty() || rnd.nextDouble() < 0.6 ? staff.get(rnd.nextInt(staff.size())) : customers.get(rnd.nextInt(customers.size())));
            String[] titles = type == TicketType.SUPPORT ? SUPPORT_TITLES : IT_TITLES;

            Ticket t = new Ticket();
            t.setId(UUID.randomUUID());
            t.setTenantId(tenant);
            t.setNumber(i + 1L);
            t.setType(type);
            t.setTitle(titles[rnd.nextInt(titles.length)] + (rnd.nextInt(4) == 0 ? " (#" + (100 + rnd.nextInt(900)) + ")" : ""));
            t.setDescription(BODIES[rnd.nextInt(BODIES.length)]);
            t.setPriority(prio);
            t.setSeverity(sev);
            t.setCategoryId(cat == null ? null : cat.getId());
            List<String> tags = new ArrayList<>();
            for (int k = rnd.nextInt(3); k > 0; k--) { String tg = TAG_POOL[rnd.nextInt(TAG_POOL.length)]; if (!tags.contains(tg)) tags.add(tg); }
            t.setTagList(tags);
            t.setRequesterId(requester.getId());
            t.setCreatedAt(created);
            sla.applyDueDates(t);

            // ---- lifecycle -------------------------------------------------------------------------------------------
            TicketStatus status = ageHours < 3 ? pick(rnd, new TicketStatus[]{TicketStatus.NEW, TicketStatus.OPEN, TicketStatus.PENDING}, new int[]{55, 35, 10})
                    : ageHours < 72 ? pick(rnd, TicketStatus.values(), new int[]{12, 33, 15, 8, 20, 6, 6})
                    : pick(rnd, TicketStatus.values(), new int[]{3, 17, 12, 8, 25, 28, 7});
            long wallToResolution = Math.max(60, Duration.between(created, t.getResolutionDueAt()).toMinutes());
            long wallToResponse = Math.max(5, Duration.between(created, t.getFirstResponseDueAt()).toMinutes());
            AppUser assignee = status == TicketStatus.NEW && rnd.nextDouble() < 0.7 ? null : agents.get(rnd.nextInt(agents.size()));
            t.setAssigneeId(assignee == null ? null : assignee.getId());

            Instant responded = null;
            if (status != TicketStatus.NEW) {
                double f = rnd.nextDouble() < 0.85 ? 0.1 + rnd.nextDouble() * 0.85 : 1.1 + rnd.nextDouble();
                responded = created.plus((long) (wallToResponse * f), ChronoUnit.MINUTES);
                if (responded.isAfter(now.minus(2, ChronoUnit.MINUTES))) responded = now.minus(2, ChronoUnit.MINUTES);
                if (!responded.isAfter(created)) { status = TicketStatus.NEW; responded = null; }
            }
            Instant resolved = null;
            if (status == TicketStatus.RESOLVED || status == TicketStatus.CLOSED) {
                double f = rnd.nextDouble() < 0.72 ? 0.2 + rnd.nextDouble() * 0.75 : 1.05 + rnd.nextDouble() * 0.9;
                resolved = created.plus((long) (wallToResolution * f), ChronoUnit.MINUTES);
                if (resolved.isAfter(now.minus(1, ChronoUnit.MINUTES)) || responded == null || resolved.isBefore(responded)) {
                    resolved = null;
                    status = responded == null ? TicketStatus.NEW : TicketStatus.OPEN; // too young to be resolved
                }
            }
            t.setStatus(status);
            t.setFirstRespondedAt(responded);
            t.setResolvedAt(resolved);
            if (status == TicketStatus.REOPENED) t.setReopenCount(1 + rnd.nextInt(2));
            if ((status == TicketStatus.RESOLVED || status == TicketStatus.CLOSED) && rnd.nextDouble() < 0.06) t.setReopenCount(1);
            if (status == TicketStatus.PENDING || status == TicketStatus.ON_HOLD) {
                Instant pausedAt = now.minus(30L + rnd.nextInt(2000), ChronoUnit.MINUTES);
                t.setSlaPausedAt(pausedAt.isBefore(created) ? created.plusSeconds(60) : pausedAt);
            }
            SlaCalculator.Result r = sla.evaluate(t, now, 75, sla.calendar());
            t.setSlaStatus(r.status());
            t.setEscalationLevel(r.escalationLevel());
            t.setBreachNotified(r.status() == SlaStatus.BREACHED); // history only: no events are replayed for seeded data
            t.setSlaCheckedAt(now);
            Instant lastTouch = resolved != null ? resolved : responded != null ? responded.plus(rnd.nextInt(600), ChronoUnit.MINUTES) : created;
            if (lastTouch.isAfter(now)) lastTouch = now;
            t.setUpdatedAt(lastTouch);
            em.persist(t);

            if (rnd.nextDouble() < 0.12) {
                TicketAttachment a = new TicketAttachment();
                a.setId(UUID.randomUUID());
                a.setTenantId(tenant);
                a.setTicketId(t.getId());
                a.setFileName(rnd.nextBoolean() ? "screenshot-" + (1 + rnd.nextInt(9)) + ".png" : "error-log-" + (1 + rnd.nextInt(9)) + ".txt");
                a.setContentType(a.getFileName().endsWith(".png") ? "image/png" : "text/plain");
                a.setSizeBytes(2_048L + rnd.nextInt(900_000));
                a.setCreatedAt(created);
                em.persist(a);
            }

            // ---- activity + comments -----------------------------------------------------------------------------------
            activity(t, TicketActivity.CREATED, "Ticket created (" + prio + ", " + type + ")", requester, created, tenant);
            if (assignee != null) activity(t, TicketActivity.ASSIGNED, "Assigned to " + assignee.getDisplayName(), assignee, created.plusSeconds(90), tenant);
            if (responded != null) activity(t, TicketActivity.STATUS_CHANGED, "NEW -> OPEN", assignee, responded, tenant);
            if (status != TicketStatus.NEW && status != TicketStatus.OPEN)
                activity(t, TicketActivity.STATUS_CHANGED, "OPEN -> " + status, assignee, lastTouch, tenant);
            if (responded != null && assignee != null) {
                int n = rnd.nextInt(5);
                Instant end = resolved != null ? resolved : now;
                for (int c = 0; c < n; c++) {
                    long span = Math.max(1, Duration.between(responded, end).toMinutes());
                    Instant at = responded.plus(c == 0 ? 0 : (long) (rnd.nextDouble() * span), ChronoUnit.MINUTES);
                    if (at.isAfter(now)) at = now;
                    boolean fromCustomer = c % 2 == 1 && requester.getRoles().equals(EnumSet.of(Role.CUSTOMER));
                    boolean internal = !fromCustomer && c > 0 && rnd.nextDouble() < 0.25;
                    TicketComment cm = new TicketComment();
                    cm.setId(UUID.randomUUID());
                    cm.setTenantId(tenant);
                    cm.setTicketId(t.getId());
                    cm.setAuthorId(fromCustomer ? requester.getId() : assignee.getId());
                    cm.setBody(fromCustomer ? CUSTOMER_REPLIES[rnd.nextInt(CUSTOMER_REPLIES.length)]
                            : internal ? INTERNAL_NOTES[rnd.nextInt(INTERNAL_NOTES.length)] : AGENT_REPLIES[rnd.nextInt(AGENT_REPLIES.length)]);
                    cm.setVisibility(internal ? Visibility.INTERNAL : Visibility.PUBLIC);
                    cm.setAiGenerated(false);
                    cm.setCreatedAt(at);
                    em.persist(cm);
                    activity(t, TicketActivity.COMMENTED, cm.getVisibility() + " comment", fromCustomer ? requester : assignee, at, tenant);
                }
            }
            if (i % 25 == 24) { em.flush(); em.clear(); }
        }
        em.flush();
        TicketCounter counter = em.find(TicketCounter.class, tenant);
        counter.setNextNumber(ages.size() + 1L);
        em.flush();
    }

    private void activity(Ticket t, String type, String details, AppUser actor, Instant at, UUID tenant) {
        TicketActivity a = new TicketActivity();
        a.setId(UUID.randomUUID());
        a.setTenantId(tenant);
        a.setTicketId(t.getId());
        a.setActorId(actor == null ? null : actor.getId());
        a.setActorName(actor == null ? "System" : actor.getDisplayName());
        a.setType(type);
        a.setDetails(details);
        a.setCreatedAt(at);
        em.persist(a);
    }

    private static <T> T pick(Random rnd, T[] values, int[] weights) {
        int total = 0;
        for (int w : weights) total += w;
        int r = rnd.nextInt(total);
        for (int i = 0; i < values.length; i++) {
            r -= weights[i];
            if (r < 0) return values[i];
        }
        return values[values.length - 1];
    }
}
