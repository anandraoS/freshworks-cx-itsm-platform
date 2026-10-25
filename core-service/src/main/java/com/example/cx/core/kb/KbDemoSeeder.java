package com.example.cx.core.kb;

import com.example.cx.core.docstore.DocumentStore;
import com.example.cx.core.tickets.Visibility;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Synthetic KB content for the demo tenants (12 articles + 6 categories per tenant: 8 PUBLISHED/PUBLIC, 2 PUBLISHED/INTERNAL,
 * 1 DRAFT/PUBLIC, 1 DRAFT/INTERNAL). Called by the demo seeder INSIDE its tenant-bound transaction (this class never binds a
 * tenant itself). Writes rows directly (no audit / outbox noise) and a v1 snapshot per article. All text is synthetic.
 */
@Component
public class KbDemoSeeder {
    private record Cat(String name) {}

    private record Art(String title, String summary, String body, int cat, Visibility vis, ArticleStatus status) {}

    private static final List<Cat> CATS = List.of(new Cat("Account & Access"), new Cat("Billing & Payments"), new Cat("Troubleshooting"),
            new Cat("Network & VPN"), new Cat("Hardware & Devices"), new Cat("Internal Procedures"));

    private static final List<Art> ARTS = List.of(
            new Art("How to reset your password", "Step by step password reset for locked or forgotten passwords.",
                    "If you forgot your password or your account is locked, open the sign-in page and choose Forgot password. "
                            + "Enter your e-mail address and follow the link we send you. The link expires after 30 minutes. "
                            + "If no message arrives, check the spam folder and confirm the address with support. Passwords need 12 characters.",
                    0, Visibility.PUBLIC, ArticleStatus.PUBLISHED),
            new Art("Understanding your invoice and VAT", "How invoices are built, where VAT appears and how to request a corrected invoice.",
                    "Each invoice lists the net amount, the VAT rate for the delivery country and the total. If the VAT looks wrong, "
                            + "check the billing country in your profile and ask support for a corrected invoice. Invoices are available as PDF for 24 months.",
                    1, Visibility.PUBLIC, ArticleStatus.PUBLISHED),
            new Art("Requesting a refund for a returned order", "Refund timelines and what to do when a refund has not arrived.",
                    "Refunds are issued to the original payment method within 5 to 7 business days after the returned items are received. "
                            + "If the refund has not arrived after 10 business days, contact support with your order number. Card refund timing depends on your bank.",
                    1, Visibility.PUBLIC, ArticleStatus.PUBLISHED),
            new Art("Troubleshooting checkout and saved card errors", "Fixes for declined cards, promo codes and checkout that will not complete.",
                    "If checkout fails with a saved card, remove the card and add it again, then retry. Make sure the promo code is valid for "
                            + "the items in the basket. Clear the browser cache or try another browser. A duplicate charge usually disappears as a pending authorisation.",
                    2, Visibility.PUBLIC, ArticleStatus.PUBLISHED),
            new Art("Connecting to the corporate VPN", "Install the VPN client and connect from home or while travelling.",
                    "Install the approved VPN client from the self-service portal, sign in with your work account and approve the multi-factor prompt. "
                            + "Choose the nearest gateway. If the connection fails, check that your Wi-Fi network allows VPN traffic and that the client is up to date.",
                    3, Visibility.PUBLIC, ArticleStatus.PUBLISHED),
            new Art("VPN keeps disconnecting: common fixes", "What to try when the VPN drops every few minutes.",
                    "A VPN that disconnects every few minutes is usually caused by an unstable Wi-Fi signal, power saving on the network adapter or an outdated client. "
                            + "Switch to a wired connection, disable adapter power saving, update the VPN client and restart. If the drops continue, send the client log to the service desk.",
                    3, Visibility.PUBLIC, ArticleStatus.PUBLISHED),
            new Art("Setting up multi-factor authentication", "Enrol an authenticator app and what to do if you lose the device.",
                    "Open Security settings, choose Add authenticator and scan the QR code with an authenticator app. Store the recovery codes safely. "
                            + "If you lose the device, ask the service desk to reset your MFA token after identity verification.",
                    0, Visibility.PUBLIC, ArticleStatus.PUBLISHED),
            new Art("Printer offline: quick checklist", "Bring an offline office printer back in five checks.",
                    "Check power and paper, confirm the printer shows an IP address, make sure your laptop is on the office network, remove and re-add the printer, "
                            + "and restart the print spooler. If the printer stays offline report the printer name and floor to the service desk.",
                    4, Visibility.PUBLIC, ArticleStatus.PUBLISHED),
            new Art("Handling duplicate charge escalations", "Internal runbook for agents: verify, reverse and communicate duplicate charges.",
                    "Verify both charges in the payment console, confirm one is a pending authorisation, and only then request a reversal. "
                            + "Record the transaction ids as an INTERNAL note. Never paste card data into a ticket. Escalate to finance when the amount exceeds the agent limit.",
                    5, Visibility.INTERNAL, ArticleStatus.PUBLISHED),
            new Art("Agent runbook: major incident communication", "Internal checklist for announcing and updating a major incident.",
                    "Declare the incident, assign an incident lead, post a first update within 15 minutes and then every 30 minutes. "
                            + "Link all related tickets to this article. Use PUBLIC replies for customer-facing status and INTERNAL notes for diagnostics.",
                    5, Visibility.INTERNAL, ArticleStatus.PUBLISHED),
            new Art("Mobile app crashes on startup", "Draft: crash on launch after the latest app update.",
                    "Draft content under review. Clear the app cache, reinstall the latest version and sign in again. "
                            + "Known issue with older operating system versions; a fix is planned in the next release.",
                    2, Visibility.PUBLIC, ArticleStatus.DRAFT),
            new Art("Security patch rollout checklist", "Draft internal checklist for workstation security patches.",
                    "Draft content under review. Identify affected workstations, schedule the maintenance window, notify owners, "
                            + "apply the patch, verify the version and record exceptions in the change ticket.",
                    5, Visibility.INTERNAL, ArticleStatus.DRAFT));

    private final KbCategoryRepository categories;
    private final KbArticleRepository articles;
    private final DocumentStore docs;

    public KbDemoSeeder(KbCategoryRepository categories, KbArticleRepository articles, DocumentStore docs) {
        this.categories = categories;
        this.articles = articles;
        this.docs = docs;
    }

    /** Must run inside a transaction with {@code tenant} bound (the caller owns both). */
    public void seed(UUID tenant, UUID authorId) {
        Instant now = Instant.now();
        UUID[] catIds = new UUID[CATS.size()];
        for (int i = 0; i < CATS.size(); i++) {
            KbCategory c = new KbCategory();
            c.setId(UUID.randomUUID());
            c.setTenantId(tenant);
            c.setName(CATS.get(i).name());
            c.setSlug(KbService.slugify(CATS.get(i).name(), 60));
            c.setCreatedAt(now);
            categories.save(c);
            catIds[i] = c.getId();
        }
        int n = 0;
        for (Art a : ARTS) {
            Instant created = now.minus(60 - n * 4L, ChronoUnit.DAYS);
            KbArticle e = new KbArticle();
            e.setId(UUID.randomUUID());
            e.setTenantId(tenant);
            e.setTitle(a.title());
            e.setSlug(KbService.slugify(a.title(), 80));
            e.setSummary(a.summary());
            e.setBody(a.body());
            e.setCategoryId(catIds[a.cat()]);
            e.setVisibility(a.vis());
            e.setStatus(a.status());
            e.setAuthorId(authorId);
            e.setCreatedAt(created);
            e.setUpdatedAt(created.plus(1, ChronoUnit.DAYS));
            if (a.status() == ArticleStatus.PUBLISHED) e.setPublishedAt(created.plus(1, ChronoUnit.DAYS));
            articles.saveAndFlush(e);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("articleId", e.getId().toString());
            d.put("version", e.displayVersion());
            d.put("title", e.getTitle());
            d.put("summary", e.getSummary());
            d.put("body", e.getBody());
            d.put("categoryId", catIds[a.cat()].toString());
            d.put("categoryName", CATS.get(a.cat()).name());
            d.put("visibility", e.getVisibility().name());
            d.put("status", e.getStatus().name());
            d.put("editedBy", authorId.toString());
            d.put("editedByName", "Seed");
            d.put("editedAt", created.toString());
            d.put("note", "seeded");
            docs.put(tenant, KbService.VERSIONS, e.getId() + ":" + String.format("%08d", e.displayVersion()), d);
            n++;
        }
    }
}
