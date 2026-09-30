package com.mcreatik.uploader.ui;

import java.awt.Color;
import java.awt.Font;

import javax.swing.UIManager;

final class UiTheme {

    static final Color INK = new Color(0x1C1917);
    static final Color MUTED = new Color(0x78716C);
    static final Color PAPER = new Color(0xFAFAF9);
    static final Color LINE = new Color(0xE7E5E4);
    static final Color GREEN = new Color(0x15803D);
    static final Color AMBER = new Color(0xB45309);
    static final Color RED = new Color(0xB91C1C);

    private UiTheme() {
    }

    static void apply() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // default look and feel is fine
        }
    }

    static Font font(int style, float size) {
        return UIManager.getFont("Label.font").deriveFont(style, size);
    }
}
