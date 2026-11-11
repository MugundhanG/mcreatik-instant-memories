package com.mcreatik.uploader.ui.components;

import java.awt.Cursor;

import javax.swing.JButton;

import com.formdev.flatlaf.FlatClientProperties;

/** A text link ("Forgot password?"), coloured from a theme token and restyled when the theme changes. */
public final class LinkButton extends JButton {
    private final String token;

    public LinkButton(String text, String token) {
        super(text);
        this.token = token;
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFocusable(true);
        updateUI();
    }

    @Override
    public void updateUI() {
        if (token != null) {
            putClientProperty(FlatClientProperties.STYLE, "buttonType: borderless; foreground: " + Ui.hex(token)
                    + "; hoverBackground: " + Ui.hex("hover") + "; arc: 8; margin: 3,8,3,8; focusWidth: 0");
        }
        super.updateUI();
        setFont(Ui.font(java.awt.Font.PLAIN, 12.5f));
    }
}
