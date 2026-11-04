package com.mcreatik.uploader.ui;

import java.awt.Font;
import java.awt.Image;
import java.awt.LayoutManager;
import java.util.List;
import java.util.Objects;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;

import com.formdev.flatlaf.FlatClientProperties;
import com.mcreatik.uploader.ui.components.Ui;
import com.mcreatik.uploader.ui.theme.Theme;

/**
 * App name, logo and the shortcuts the setup window uses. Colours are theme tokens (see {@code ui/theme}); the
 * constants below are token names, not colours, so both themes work everywhere.
 */
public final class UiTheme {

    public static final String APP_NAME = "McreatiK Instant Memories Uploader";

    static final String INK = "text";
    static final String MUTED = "muted";
    static final String GOLD = "brand";
    static final String RED = "red";
    static final String GREEN = "green";

    private UiTheme() {
    }

    /** Installs the saved theme (Light by default). Safe to call more than once. */
    public static void apply() {
        Theme.applyAtStartup();
    }

    static Font font(int style, float size) {
        return Ui.font(style, size);
    }

    static Ui.TLabel label(String text, int style, float size, String token) {
        return Ui.label(text, style, size, token);
    }

    static Ui.Surface card(LayoutManager layout) {
        return Ui.Surface.card(layout);
    }

    static JButton primary(String text) {
        return new Ui.Btn(text, Ui.Kind.PRIMARY, null);
    }

    static JButton secondary(String text) {
        return new Ui.Btn(text, Ui.Kind.SECONDARY, null);
    }

    static void style(JComponent c, String flatStyle) {
        c.putClientProperty(FlatClientProperties.STYLE, flatStyle);
    }

    public static ImageIcon logo(int size) {
        java.net.URL url = UiTheme.class.getResource(size > 64 ? "logo-256.png" : "logo-64.png");
        ImageIcon icon = new ImageIcon(Objects.requireNonNull(url, "logo missing"));
        return new ImageIcon(icon.getImage().getScaledInstance(size, size, Image.SCALE_SMOOTH));
    }

    public static List<Image> windowIcons() {
        return List.of(logo(16).getImage(), logo(32).getImage(), logo(64).getImage(), logo(256).getImage());
    }
}
