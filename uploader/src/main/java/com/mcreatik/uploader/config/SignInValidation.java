package com.mcreatik.uploader.config;

import java.util.regex.Pattern;

/** Checks what the photographer types on the sign-in screen. Pure functions: a null result means "fine". */
public final class SignInValidation {

    public static final int EMAIL_MAX = 255;
    /** The server's limit. */
    public static final int PASSWORD_MAX = 200;

    private static final String LABEL = "[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?";
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+'-]+@(?:" + LABEL + "\\.)+[A-Za-z]{2,}$");

    private SignInValidation() {
    }

    /** The email as it is sent: surrounding spaces removed. */
    public static String normalizeEmail(String raw) {
        return raw == null ? "" : raw.trim();
    }

    public static String emailError(String raw) {
        String email = normalizeEmail(raw);
        if (email.isEmpty()) {
            return "Enter your email";
        }
        if (email.length() > EMAIL_MAX || !EMAIL.matcher(email).matches()) {
            return "Enter a valid email, e.g. name@studio.com";
        }
        return null;
    }

    /** The password is never trimmed: spaces may be part of it. */
    public static String passwordError(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "Enter your password";
        }
        if (raw.length() > PASSWORD_MAX) {
            return "Password is too long (" + PASSWORD_MAX + " characters at most)";
        }
        return null;
    }

    public static boolean canSubmit(String email, String password) {
        return emailError(email) == null && passwordError(password) == null;
    }
}
