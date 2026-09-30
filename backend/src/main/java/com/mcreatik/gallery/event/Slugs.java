package com.mcreatik.gallery.event;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

import com.mcreatik.gallery.common.Tokens;

public final class Slugs {

    public static final Pattern VALID = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{1,78}[a-z0-9])$");

    private Slugs() {
    }

    /** "Arun & Priya Wedding" → "arun-priya-wedding-7k3d". The suffix makes galleries hard to guess. */
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
        return base + "-" + Tokens.randomSuffix(4);
    }

    public static boolean isValid(String slug) {
        return slug != null && VALID.matcher(slug).matches() && !slug.contains("--");
    }
}
