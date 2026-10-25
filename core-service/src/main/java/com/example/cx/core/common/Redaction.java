package com.example.cx.core.common;

import java.util.regex.Pattern;

/** Masks values of sensitive-looking keys in free text before it reaches logs or audit details. */
public final class Redaction {
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(password|passwd|secret|token|authorization|api[_-]?key|webhook[_-]?url)([\\\"'=: ]+)(Bearer )?[^,\\\"'\\s}&]+");

    private Redaction() {}

    public static String redact(String s) {
        return s == null ? null : SENSITIVE.matcher(s).replaceAll("$1$2***");
    }
}
