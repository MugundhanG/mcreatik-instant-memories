package com.mcreatik.uploader.ui.theme;

import java.awt.Color;

import javax.swing.UIManager;

/**
 * The one place colours come from: {@code Tok.c("card")} reads token {@code McK.card} of the active theme.
 * Components call it while painting (or in {@code updateUI()}), never caching a colour, so a theme switch
 * recolours everything.
 */
public final class Tok {

    private Tok() {
    }

    public static Color c(String token) {
        Color c = UIManager.getColor("McK." + token);
        return c != null ? c : Fallback.of(token);
    }

    /** Light values, used only if the theme failed to load. */
    private static final class Fallback {
        static Color of(String token) {
            return switch (token) {
                case "text" -> new Color(0x111827);
                case "muted", "grey" -> new Color(0x5F6673);
                case "card", "header", "selected", "secondary" -> Color.WHITE;
                case "green", "primary" -> new Color(0x15803D);
                case "red" -> new Color(0xB91C1C);
                case "blue" -> new Color(0x1D4ED8);
                case "amber" -> new Color(0xB45309);
                default -> new Color(0xE5E7EB);
            };
        }
    }
}
