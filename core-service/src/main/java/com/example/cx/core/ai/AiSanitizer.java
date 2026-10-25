package com.example.cx.core.ai;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Input sanitisation for everything that is sent to an {@link AiProvider}, and output cleaning for everything that comes back.
 *
 * INPUT pipeline (ticket text is DATA, never instructions):
 *   1. Unicode NFKC (defeats full-width / compatibility-character tricks), drop control and zero-width characters, strip HTML tags.
 *   2. Drop every SENTENCE that contains an instruction-like control phrase ("ignore previous instructions", role markers,
 *      "approve without review", "set priority to ..."). Dropping the sentence (not just the phrase) means the remainder of an
 *      injected command cannot steer keyword rules either. The number of dropped sentences is reported (injectionSignals).
 *   3. Redact secrets and personal data: bearer tokens / JWTs / key=value secrets, e-mail addresses, card-like numbers, phone numbers.
 *   4. Bound the length.
 * OUTPUT cleaning: strip tags / control characters / URLs and bound the length. Output is never parsed for commands anywhere.
 *
 * This is defence in depth, NOT a guarantee: regexes cannot catch every paraphrase. The real protection is architectural
 * (see docs/ai-safety-notes.md): suggestions are inert data, nothing is applied or sent without explicit human approval.
 */
public final class AiSanitizer {
    public record Result(String text, Map<String, Integer> redactions, int injectionSignals, boolean truncated) {}

    private static final Pattern TAGS = Pattern.compile("<[^>\\n]{0,200}>");
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200F\\u2028\\u2029\\u2060\\uFEFF]");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");
    private static final Pattern SENTENCES = Pattern.compile("(?<=[.!?])\\s+|\\R+");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");

    private static final List<Pattern> INJECTION = List.of(
            p("ignore\\s+(?:all\\s+|any\\s+|the\\s+|your\\s+)?(?:previous|prior|above|earlier|preceding|system)\\s+(?:instructions?|prompts?|rules?|messages?|directions?)"),
            p("disregard\\s+(?:all\\s+|the\\s+|any\\s+|your\\s+)?(?:previous|prior|above|instructions?|rules?|system|guidelines?)"),
            p("forget\\s+(?:everything|all\\s+(?:previous|prior)|your\\s+(?:instructions|rules)|what\\s+you)"),
            p("\\byou\\s+are\\s+now\\b"), p("from\\s+now\\s+on\\s*,?\\s+you"),
            p("\\bpretend\\s+(?:to\\s+be|you\\s+are)"),
            p("\\bact\\s+as\\s+(?:an?\\s+|the\\s+)?(?:admin|administrator|system|root|developer|dan|assistant)"),
            p("(?:reveal|show|print|repeat|output|leak|tell\\s+me)\\s+(?:me\\s+)?(?:your|the)\\s+(?:system\\s+)?(?:prompt|instructions|rules|secrets?|api\\s*keys?|passwords?|credentials)"),
            p("system\\s*prompt"), p("developer\\s+mode"), p("jail\\s*break"), p("do\\s+anything\\s+now"),
            p("\\bnew\\s+(?:instructions?|rules?)\\s*:"), p("\\boverride\\s+(?:the\\s+|all\\s+|any\\s+)?(?:rules|instructions|safety|polic(?:y|ies)|guardrails?)"),
            p("^\\s*(?:system|assistant|developer)\\s*:"), p("\\[/?(?:INST|SYS|SYSTEM)\\]"), p("<\\|?(?:im_start|im_end|system|endoftext)\\|?>"),
            p("#{2,}\\s*(?:instruction|system|assistant)"),
            p("\\b(?:approve|send|post|publish|submit)\\s+(?:this|the|it|that)\\b.{0,40}\\b(?:to\\s+the\\s+customer|without|automatically)"),
            p("\\bwithout\\s+(?:any\\s+)?(?:human\\s+)?(?:review|approval|confirmation)"),
            p("\\bmark\\s+(?:this\\s+)?(?:ticket\\s+)?(?:as\\s+)?(?:urgent|resolved|closed|critical)"),
            p("\\bset\\s+(?:the\\s+)?(?:ticket\\s+)?(?:priority|status|severity|assignee)\\s+(?:to|as)\\b"),
            p("\\bchange\\s+(?:the\\s+)?(?:ticket\\s+)?(?:priority|status|assignee|severity)\\b"),
            p("\\b(?:delete|close|escalate|reassign)\\s+(?:this\\s+|the\\s+)?ticket"),
            p("\\b(?:call|invoke|use)\\s+(?:the\\s+)?(?:api|tool|function|plugin)\\b"),
            p("\\bexecute\\s+(?:the\\s+)?(?:following|command|script|code)"));

    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*");
    private static final Pattern KEYVAL = Pattern.compile("(?i)\\b(api[_-]?key|secret|token|password|passwd|pwd)\\b\\s*[:=]\\s*\\S+");
    private static final Pattern KEYSHAPE = Pattern.compile("\\b(?:sk|pk|ghp|gho|xox[abp])[-_][A-Za-z0-9_-]{16,}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");
    private static final Pattern PHONE_INTL = Pattern.compile("(?<![\\w+])\\+\\d{1,3}[\\s.-]?\\(?\\d{1,4}\\)?(?:[\\s.-]?\\d{2,4}){2,4}(?!\\d)");
    private static final Pattern PHONE_US = Pattern.compile("(?<![\\w])\\(?\\d{3}\\)?[\\s.-]\\d{3}[\\s.-]\\d{4}(?!\\d)");

    private AiSanitizer() {}

    private static Pattern p(String regex) { return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE); }

    public static Result sanitize(String raw, int maxLen) {
        Map<String, Integer> red = new LinkedHashMap<>();
        String t = normalise(raw);

        int signals = 0;
        StringBuilder kept = new StringBuilder();
        for (String sentence : SENTENCES.split(t)) {
            if (sentence.isBlank()) continue;
            if (isInstructionLike(sentence)) { signals++; continue; }
            if (kept.length() > 0) kept.append(' ');
            kept.append(sentence.strip());
        }
        t = kept.toString();

        t = replace(t, BEARER, "[REDACTED_TOKEN]", "token", red);
        t = replace(t, JWT, "[REDACTED_TOKEN]", "token", red);
        t = replace(t, KEYSHAPE, "[REDACTED_TOKEN]", "token", red);
        t = replaceKeyVal(t, red);
        t = replace(t, EMAIL, "[REDACTED_EMAIL]", "email", red);
        t = replace(t, CARD, "[REDACTED_CARD]", "card", red);
        t = replace(t, PHONE_INTL, "[REDACTED_PHONE]", "phone", red);
        t = replace(t, PHONE_US, "[REDACTED_PHONE]", "phone", red);

        boolean truncated = t.length() > maxLen;
        if (truncated) t = t.substring(0, maxLen);
        return new Result(t, red, signals, truncated);
    }

    public static boolean isInstructionLike(String sentence) {
        for (Pattern pat : INJECTION) if (pat.matcher(sentence).find()) return true;
        return false;
    }

    /** Output (provider -> us): inert text only. */
    public static String cleanOutput(String s, int maxLen) {
        if (s == null) return null;
        String t = TAGS.matcher(normalise(s)).replaceAll("");
        t = URL.matcher(t).replaceAll("[link removed]").strip();
        return t.length() > maxLen ? t.substring(0, maxLen) : t;
    }

    private static String normalise(String raw) {
        String t = raw == null ? "" : Normalizer.normalize(raw, Normalizer.Form.NFKC);
        t = ZERO_WIDTH.matcher(t).replaceAll("");
        t = CONTROL.matcher(t).replaceAll(" ");
        t = TAGS.matcher(t).replaceAll(" ");
        return t.replace('\t', ' ');
    }

    private static String replace(String text, Pattern pat, String repl, String kind, Map<String, Integer> red) {
        Matcher m = pat.matcher(text);
        int n = 0;
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            n++;
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        if (n > 0) red.merge(kind, n, Integer::sum);
        return sb.toString();
    }

    private static String replaceKeyVal(String text, Map<String, Integer> red) {
        Matcher m = KEYVAL.matcher(text);
        int n = 0;
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            n++;
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + "=[REDACTED_SECRET]"));
        }
        m.appendTail(sb);
        if (n > 0) red.merge("secret", n, Integer::sum);
        return sb.toString();
    }
}
