package com.mcreatik.gallery.event;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

import com.mcreatik.gallery.common.Tokens;

public final class Slugs {

    public static final Pattern VALID = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{1,78}[a-z0-9])$");

    private Slugs() {
    }

    /** Random characters after the name: 31^10 ≈ 800 trillion addresses, so a gallery cannot be found by guessing. */
    static final int SUFFIX_LENGTH = 10;

    /** "Arun & Priya Wedding" → "arun-priya-wedding-7k3dqx9mzp". */
    public static String generate(String name) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (base.length() > 60) {
            base = base.substring(0, 60).replaceAll("-+$", "");
        }
        if (base.isEmpty()) {
            base = "event";
        }
        return base + "-" + Tokens.randomSuffix(SUFFIX_LENGTH);
    }

    public static boolean isValid(String slug) {
        return slug != null && VALID.matcher(slug).matches() && !slug.contains("--");
    }
}
