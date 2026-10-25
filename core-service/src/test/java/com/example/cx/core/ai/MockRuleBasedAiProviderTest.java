package com.example.cx.core.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.cx.core.ai.AiProvider.AiRequest;
import com.example.cx.core.ai.AiProvider.AiResult;
import com.example.cx.core.ai.AiProvider.ArticleOption;
import com.example.cx.core.ai.AiProvider.CategoryOption;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Determinism + a small GOLDEN SET. The golden set is a regression test of fixed rules (each expected value is what the rules are
 * DESIGNED to output for that text); it shows that nothing drifted, NOT how intelligent the engine is. See docs/ai-safety-notes.md.
 */
class MockRuleBasedAiProviderTest {
    static final List<ArticleOption> ARTICLES = List.of(
            art("a1", "How to reset your password", "Reset a forgotten or locked password", true),
            art("a2", "Understanding your invoice and VAT", "How invoices are built and how to request a corrected invoice", true),
            art("a3", "Connecting to the corporate VPN", "Install the VPN client and connect", true),
            art("a4", "VPN keeps disconnecting: common fixes", "What to try when the VPN drops", true),
            art("a5", "Printer offline: quick checklist", "Bring an offline printer back", true),
            art("a6", "Refund timelines for returned orders", "When a refund arrives after a return", true),
            art("a7", "Agent runbook: duplicate charges", "Internal procedure for duplicate charges", false));

    static final List<CategoryOption> CATS = List.of("Billing", "Account Access", "Bug Report", "Incident", "Hardware", "Service Request",
            "Access Request", "Product Question").stream().map(n -> new CategoryOption(UUID.nameUUIDFromBytes(n.getBytes()), n)).toList();

    static ArticleOption art(String key, String title, String summary, boolean pub) {
        return new ArticleOption(UUID.nameUUIDFromBytes(key.getBytes()), title, summary, pub);
    }

    record Golden(String title, String description, String category, String priority, String topArticleTitle) {}

    static final List<Golden> GOLDEN = List.of(
            new Golden("Cannot log in after password reset", "I am locked out of my account since this morning.", "Account Access", "HIGH", "How to reset your password"),
            new Golden("Invoice shows wrong VAT", "The VAT on my last invoice looks incorrect, please send a corrected invoice.", "Billing", "MEDIUM", "Understanding your invoice and VAT"),
            new Golden("VPN disconnects every few minutes", "The VPN drops constantly while I work from home.", "Incident", "MEDIUM", "VPN keeps disconnecting: common fixes"),
            new Golden("Printer on floor 3 offline", "The office printer shows offline for everyone on the floor.", "Hardware", "URGENT", "Printer offline: quick checklist"),
            new Golden("Production outage: nobody can reach the server", "Complete outage for all users since 09:00.", "Incident", "URGENT", null),
            new Golden("App crashes on startup", "The mobile app crashes every time I open it.", "Bug Report", "HIGH", null),
            new Golden("Question about loyalty points", "How do I see my loyalty points balance?", "Product Question", "LOW", null),
            new Golden("Refund not received", "My refund for the returned order has not arrived.", "Billing", "MEDIUM", "Refund timelines for returned orders"),
            new Golden("New starter hardware request", "Please provision a laptop and install the licence for our new starter.", "Service Request", "MEDIUM", null),
            new Golden("Request access to finance shared drive", "I need permission to the finance folder.", "Access Request", "MEDIUM", null),
            new Golden("Possible security incident", "We think an account was compromised.", "Account Access", "URGENT", null),
            new Golden("Hello team", "Nothing specific to report right now.", null, "MEDIUM", null));

    private final MockRuleBasedAiProvider provider = new MockRuleBasedAiProvider();

    private AiResult run(String title, String desc) {
        return provider.analyze(new AiRequest(title, desc, null, CATS, ARTICLES));
    }

    @Test
    void sameInputGivesIdenticalOutputEveryTime() {
        AiResult first = run("Invoice shows wrong VAT", "The VAT on my last invoice looks incorrect.");
        for (int i = 0; i < 20; i++) assertThat(new MockRuleBasedAiProvider().analyze(
                new AiRequest("Invoice shows wrong VAT", "The VAT on my last invoice looks incorrect.", null, CATS, ARTICLES))).isEqualTo(first);
    }

    @Test
    void goldenSetMatchesTheDesignedRuleOutputs() {
        int correct = 0, checks = 0;
        for (Golden g : GOLDEN) {
            AiResult r = run(g.title(), g.description());
            checks += 3;
            if (Objects.equals(r.categoryName(), g.category())) correct++;
            else System.out.println("GOLDEN MISMATCH category: " + g.title() + " -> " + r.categoryName());
            if (g.priority().equals(r.priority())) correct++;
            else System.out.println("GOLDEN MISMATCH priority: " + g.title() + " -> " + r.priority());
            String top = r.articles().isEmpty() ? null : r.articles().get(0).title();
            if (Objects.equals(top, g.topArticleTitle())) correct++;
            else System.out.println("GOLDEN MISMATCH article: " + g.title() + " -> " + top);
            assertThat(r.confidence()).isBetween(0.30, 0.95);
            assertThat(r.explanation()).contains("not machine learning");
        }
        System.out.println("GOLDEN SET: " + correct + "/" + checks + " checks match (" + GOLDEN.size() + " cases)");
        assertThat(correct).as("golden-set checks that match the designed outputs").isEqualTo(checks);
    }

    @Test
    void confidenceReflectsRuleMatchStrength() {
        double strong = run("Invoice and refund: charged twice on my card", "The payment and VAT on the invoice are wrong, I cannot see the refund.").confidence();
        double weak = run("Hello team", "Nothing specific.").confidence();
        assertThat(strong).isGreaterThan(weak);
        assertThat(weak).isEqualTo(0.30);
    }

    @Test
    void replyNeverCopiesTicketTextAndOnlyCitesPublicArticles() {
        AiResult r = run("Duplicate charge on my card", "Order 998877 secret-token-text-xyz was charged twice, duplicate charge please advise.");
        assertThat(r.reply()).doesNotContain("998877", "secret-token-text-xyz").doesNotContain("Agent runbook");
        assertThat(r.reply()).startsWith("Hello,").endsWith("Support Team");
    }

    @Test
    void articleScoreIsOverlapBased() {
        var stems = java.util.Set.of("vpn", "disconnect");
        assertThat(MockRuleBasedAiProvider.articleScore(stems, ARTICLES.get(3))).isEqualTo(1.0);
        assertThat(MockRuleBasedAiProvider.articleScore(stems, ARTICLES.get(5))).isEqualTo(0.0);
    }
}
