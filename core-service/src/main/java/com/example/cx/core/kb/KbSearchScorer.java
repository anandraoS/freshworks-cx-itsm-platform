package com.example.cx.core.kb;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Portable keyword scoring (no database features needed, so it behaves identically on H2 and PostgreSQL).
 *
 * score = sum over query tokens of  5 * [token in title] + 3 * [token in summary] + min(occurrences in body, 5)
 *       + 10 if the whole normalised query appears in the title, + 5 if it appears in the summary.
 * Deterministic and explainable; it is NOT a relevance model. Tokens: lower-case alphanumerics (>= 2 chars), stop-words removed,
 * at most {@value #MAX_TOKENS} distinct tokens (bounds the SQL LIKE fan-out and the scoring cost).
 */
public final class KbSearchScorer {
    public static final int MAX_TOKENS = 8;
    private static final Set<String> STOP = Set.of("the", "and", "for", "are", "but", "not", "you", "your", "with", "this", "that",
            "from", "have", "has", "was", "were", "will", "can", "could", "how", "what", "when", "why", "who", "our", "out", "all",
            "any", "its", "into", "than", "then", "there", "their", "them", "they", "been", "does", "did", "get", "got", "please",
            "need", "help", "hello", "thanks", "thank", "after", "before", "about", "just", "also", "very",
            "to", "of", "in", "on", "at", "is", "it", "be", "by", "or", "an", "as", "my", "me", "we", "do", "if", "so", "no", "up", "am", "us");

    private KbSearchScorer() {}

    public static Set<String> tokens(String text) { return tokens(text, MAX_TOKENS); }

    public static Set<String> tokens(String text, int max) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        for (String t : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() >= 2 && !STOP.contains(t)) {
                out.add(t);
                if (out.size() >= max) break;
            }
        }
        return out;
    }

    /** Very small suffix stripper (ing / ed / es / s) so "disconnecting", "disconnects" and "disconnected" meet. Not a real stemmer. */
    public static String stem(String t) {
        for (String suf : new String[] {"ing", "ed", "es", "s"}) {
            if (t.endsWith(suf) && t.length() - suf.length() >= 4) return t.substring(0, t.length() - suf.length());
        }
        return t;
    }

    public static String normalise(String text) {
        return text == null ? "" : String.join(" ", text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")).trim();
    }

    public static int score(Set<String> tokens, String normalisedQuery, String title, String summary, String body) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        String s = summary == null ? "" : summary.toLowerCase(Locale.ROOT);
        String b = body == null ? "" : body.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String tok : tokens) {
            if (t.contains(tok)) score += 5;
            if (s.contains(tok)) score += 3;
            score += Math.min(occurrences(b, tok), 5);
        }
        if (normalisedQuery.length() >= 3) {
            if (normalise(t).contains(normalisedQuery)) score += 10;
            if (normalise(s).contains(normalisedQuery)) score += 5;
        }
        return score;
    }

    private static int occurrences(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
            if (n >= 5) break;
        }
        return n;
    }
}
