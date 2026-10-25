package com.example.cx.core.ai;

import com.example.cx.core.kb.KbSearchScorer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * DEFAULT provider. A DETERMINISTIC, RULE-BASED keyword engine. It is NOT machine learning, NOT an LLM and does not "understand"
 * anything: it counts keyword matches against fixed tables, so identical input always yields identical output (tested).
 * "Confidence" is the strength of the rule matches (how many rules fired), NOT a calibrated probability.
 *
 *  - summary   : extractive (subject + first two sentences), no generation.
 *  - category  : the category whose keyword list matches most (title matches count double); ties resolved by table order.
 *  - priority  : URGENT keywords > HIGH keywords > LOW keywords > MEDIUM default.
 *  - articles  : keyword overlap between the ticket and each published article's title/summary (see {@link #articleScore}).
 *  - reply     : fixed template per category; never copies ticket text, so it cannot leak ticket data.
 */
@Component
@ConditionalOnProperty(name = "cx.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockRuleBasedAiProvider implements AiProvider {
    public static final String NAME = "MOCK_RULE_BASED";

    private static final Map<String, List<String>> CATEGORY_RULES = new LinkedHashMap<>();
    private static final Map<String, String> REPLY_PARAGRAPH = new LinkedHashMap<>();
    private static final List<String> URGENT = List.of("outage", "data loss", "breach", "ransomware", "production down",
            "all users", "everyone", "cannot work", "security incident", "compromised");
    private static final List<String> HIGH = List.of("cannot", "unable", "blocked", "crash", "crashes", "fails", "failing",
            "charged twice", "duplicate charge", "asap", "urgent", "error", "locked out", "down");
    private static final List<String> LOW = List.of("question", "how do", "how to", "feature request", "suggestion",
            "nice to have", "when possible", "no rush");

    static {
        CATEGORY_RULES.put("Billing", List.of("invoice", "billing", "refund", "charge", "charged", "payment", "vat", "price", "subscription", "card", "receipt"));
        CATEGORY_RULES.put("Account Access", List.of("password", "login", "log in", "locked", "sign in", "account", "mfa", "2fa", "access denied", "reset"));
        CATEGORY_RULES.put("Bug Report", List.of("crash", "crashes", "bug", "error", "exception", "broken", "fails", "failing", "freezes", "not working"));
        CATEGORY_RULES.put("Incident", List.of("outage", "down", "vpn", "network", "wifi", "wi fi", "disconnect", "disconnects", "slow", "unreachable", "server"));
        CATEGORY_RULES.put("Hardware", List.of("laptop", "battery", "monitor", "keyboard", "printer", "hardware", "screen", "docking"));
        CATEGORY_RULES.put("Service Request", List.of("request", "install", "licence", "license", "new starter", "onboarding", "provision"));
        CATEGORY_RULES.put("Access Request", List.of("permission", "permissions", "shared drive", "shared mailbox", "access to", "entitlement"));
        CATEGORY_RULES.put("Product Question", List.of("how do", "how to", "question", "feature", "where can", "can i", "what is"));

        REPLY_PARAGRAPH.put("Billing", "We are reviewing the billing details and will confirm any correction or refund as soon as possible.");
        REPLY_PARAGRAPH.put("Account Access", "To regain access, please use the password reset option on the sign-in page. If that does not work, we will help you further.");
        REPLY_PARAGRAPH.put("Bug Report", "We have noted the error and are investigating it. Any steps or screenshots you can share will help us.");
        REPLY_PARAGRAPH.put("Incident", "We are investigating the service disruption and will update you as soon as we know more.");
        REPLY_PARAGRAPH.put("Hardware", "We will check the device with you and arrange a repair or replacement if needed.");
        REPLY_PARAGRAPH.put("Service Request", "We have logged your request and will confirm the next steps shortly.");
        REPLY_PARAGRAPH.put("Access Request", "We have logged your access request and will confirm once it has been reviewed.");
        REPLY_PARAGRAPH.put("Product Question", "We hope the information below answers your question.");
    }

    @Override
    public String name() { return NAME; }

    @Override
    public AiResult analyze(AiRequest req) {
        String title = req.title() == null ? "" : req.title();
        String text = (title + ". " + (req.description() == null ? "" : req.description())).toLowerCase(Locale.ROOT);
        String norm = " " + String.join(" ", text.split("[^a-z0-9]+")) + " ";
        String titleNorm = " " + String.join(" ", title.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) + " ";

        // --- category
        String bestCat = null;
        int bestScore = 0;
        List<String> bestMatched = List.of();
        for (var rule : CATEGORY_RULES.entrySet()) {
            List<String> matched = new ArrayList<>();
            int score = 0;
            for (String kw : rule.getValue()) {
                if (norm.contains(" " + kw + " ")) {
                    matched.add(kw);
                    score += titleNorm.contains(" " + kw + " ") ? 2 : 1;
                }
            }
            if (score > bestScore) { bestScore = score; bestCat = rule.getKey(); bestMatched = matched; }
        }
        String categoryName = bestCat;
        boolean categoryFromTicket = false;
        if (categoryName == null && req.currentCategoryName() != null) { categoryName = req.currentCategoryName(); categoryFromTicket = true; }

        // --- priority
        List<String> urgent = matches(norm, URGENT), high = matches(norm, HIGH), low = matches(norm, LOW);
        String priority;
        List<String> prioMatched;
        if (!urgent.isEmpty()) { priority = "URGENT"; prioMatched = urgent; }
        else if (!high.isEmpty()) { priority = "HIGH"; prioMatched = high; }
        else if (!low.isEmpty()) { priority = "LOW"; prioMatched = low; }
        else { priority = "MEDIUM"; prioMatched = List.of(); }

        // --- knowledge base recommendation (keyword overlap)
        Set<String> ticketStems = KbSearchScorer.tokens(title + " " + req.description(), 24).stream()
                .map(KbSearchScorer::stem).collect(Collectors.toCollection(LinkedHashSet::new));
        List<ScoredArticle> scored = new ArrayList<>();
        Map<java.util.UUID, ArticleOption> byId = new LinkedHashMap<>();
        for (ArticleOption a : req.articles()) {
            byId.put(a.id(), a);
            double s = articleScore(ticketStems, a);
            if (s >= 0.10) scored.add(new ScoredArticle(a.id(), a.title(), s));
        }
        scored.sort(Comparator.comparingDouble(ScoredArticle::score).reversed().thenComparing(ScoredArticle::title).thenComparing(ScoredArticle::id));
        List<ScoredArticle> top = scored.size() > 3 ? new ArrayList<>(scored.subList(0, 3)) : scored;

        // --- confidence = strength of rule matches (NOT a probability)
        double conf = 0.30 + Math.min(0.30, 0.10 * bestScore) + Math.min(0.20, 0.10 * prioMatched.size())
                + (top.isEmpty() ? 0 : Math.min(0.15, top.get(0).score() * 0.15));
        conf = Math.round(Math.min(0.95, Math.max(0.30, conf)) * 100.0) / 100.0;

        // --- extractive summary
        String summary = summarise(title, req.description());

        // --- explanation
        StringBuilder ex = new StringBuilder("Rule-based analysis (fixed keyword rules, not machine learning). ");
        if (bestCat != null) ex.append("Category '").append(bestCat).append("' chosen because the text matched: ").append(String.join(", ", bestMatched)).append(". ");
        else if (categoryFromTicket) ex.append("No category rule matched; the ticket's current category '").append(categoryName).append("' is kept. ");
        else ex.append("No category rule matched. ");
        if (prioMatched.isEmpty()) ex.append("No priority keyword matched, so MEDIUM is the default. ");
        else ex.append("Priority ").append(priority).append(" chosen because of: ").append(String.join(", ", prioMatched)).append(". ");
        if (top.isEmpty()) ex.append("No knowledge base article overlaps enough with the ticket. ");
        else ex.append("Top article '").append(top.get(0).title()).append("' overlaps the ticket keywords (score ").append(String.format(Locale.ROOT, "%.2f", top.get(0).score())).append("). ");
        ex.append("Confidence reflects how many rules matched; it is not a probability.");

        // --- templated reply (PUBLIC articles only; never copies ticket text)
        String reply = reply(categoryName, top, byId);

        Map<String, Object> features = new LinkedHashMap<>();
        features.put("categoryKeywords", bestMatched);
        features.put("categoryScore", bestScore);
        features.put("priorityKeywords", prioMatched);
        features.put("ticketTokenCount", ticketStems.size());
        features.put("articleScores", top.stream().map(a -> Map.of("id", a.id().toString(), "score", a.score())).toList());
        features.put("engine", "keyword-rules-v1");
        return new AiResult(summary, categoryName, priority, conf, ex.toString(), top, reply, features);
    }

    /** (2 * title overlaps + summary overlaps) / (2 * min(ticket keywords, 6)), clamped to 1, rounded to 2 decimals. */
    static double articleScore(Set<String> ticketStems, ArticleOption a) {
        if (ticketStems.isEmpty()) return 0;
        Set<String> titleStems = KbSearchScorer.tokens(a.title(), 24).stream().map(KbSearchScorer::stem).collect(Collectors.toSet());
        Set<String> sumStems = KbSearchScorer.tokens(a.summary(), 40).stream().map(KbSearchScorer::stem).collect(Collectors.toSet());
        int th = 0, sh = 0;
        for (String t : ticketStems) {
            if (titleStems.contains(t)) th++;
            else if (sumStems.contains(t)) sh++;
        }
        double s = (2.0 * th + sh) / (2.0 * Math.min(ticketStems.size(), 6));
        return Math.round(Math.min(1.0, s) * 100.0) / 100.0;
    }

    private static List<String> matches(String norm, List<String> words) {
        List<String> out = new ArrayList<>();
        for (String w : words) if (norm.contains(" " + w + " ")) out.add(w);
        return out;
    }

    private static String summarise(String title, String description) {
        String d = description == null ? "" : description.strip();
        String[] sentences = d.split("(?<=[.!?])\\s+");
        StringBuilder sb = new StringBuilder("Subject: ").append(title.strip());
        if (!d.isEmpty()) {
            sb.append(". Details: ");
            for (int i = 0; i < Math.min(2, sentences.length); i++) { if (i > 0) sb.append(' '); sb.append(sentences[i]); }
        }
        String s = sb.toString();
        return s.length() > 600 ? s.substring(0, 597) + "..." : s;
    }

    private static String reply(String categoryName, List<ScoredArticle> top, Map<java.util.UUID, ArticleOption> byId) {
        StringBuilder sb = new StringBuilder("Hello,\n\nThank you for contacting us");
        if (categoryName != null) sb.append(" about your ").append(categoryName.toLowerCase(Locale.ROOT)).append(" request");
        sb.append(". ").append(categoryName != null && REPLY_PARAGRAPH.containsKey(categoryName) ? REPLY_PARAGRAPH.get(categoryName)
                : "We are looking into your request and will get back to you shortly.");
        for (ScoredArticle a : top) {
            ArticleOption o = byId.get(a.id());
            if (o != null && o.publicArticle()) { sb.append("\n\nThis article may help: \"").append(a.title()).append("\"."); break; }
        }
        sb.append("\n\nIf the problem continues, reply to this message and we will look into it further.\n\nBest regards,\nSupport Team");
        return sb.toString();
    }
}
