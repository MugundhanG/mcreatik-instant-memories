package com.mcreatik.uploader.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Image;
import java.awt.Insets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.border.Border;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.fonts.inter.FlatInterFont;
import com.formdev.flatlaf.ui.FlatLineBorder;

/**
 * The uploader's look: FlatLaf (a modern Swing theme) with the Inter font and the dashboard's colours, so the app
 * feels like the rest of McreatiK Instant Memories. Small helpers build the rounded cards, labels and buttons.
 */
public final class UiTheme {

    public static final String APP_NAME = "McreatiK Instant Memories Uploader";

    static final Color INK = new Color(0x1C1917);
    static final Color MUTED = new Color(0x78716C);
    static final Color PAPER = new Color(0xFAFAF9);
    static final Color CARD = Color.WHITE;
    static final Color LINE = new Color(0xE7E5E4);
    static final Color GOLD = new Color(0x8A5A1C);
    static final Color GREEN = new Color(0x15803D);
    static final Color GREEN_BG = new Color(0xF0FDF4);
    static final Color AMBER = new Color(0xB45309);
    static final Color AMBER_BG = new Color(0xFFFBEB);
    static final Color RED = new Color(0xB91C1C);
    static final Color RED_BG = new Color(0xFEF2F2);
    static final Color BLUE = new Color(0x1D4ED8);

    private static boolean applied;

    private UiTheme() {
    }

    public static synchronized void apply() {
        if (applied) {
            return;
        }
        applied = true;
        try {
            FlatInterFont.install();
            FlatLaf.setPreferredFontFamily(FlatInterFont.FAMILY);
            FlatLaf.setPreferredLightFontFamily(FlatInterFont.FAMILY_LIGHT);
            FlatLaf.setPreferredSemiboldFontFamily(FlatInterFont.FAMILY_SEMIBOLD);
            FlatLaf.setGlobalExtraDefaults(Map.of("@accentColor", "#1C1917"));
            FlatLightLaf.setup();
            UIManager.put("Panel.background", PAPER);
            UIManager.put("Component.arc", 10);
            UIManager.put("Button.arc", 10);
            UIManager.put("TextComponent.arc", 10);
            UIManager.put("CheckBox.arc", 6);
            UIManager.put("ProgressBar.arc", 999);
            UIManager.put("ProgressBar.foreground", GOLD);
            UIManager.put("Component.focusWidth", 1);
            UIManager.put("Button.innerFocusWidth", 0);
            UIManager.put("ScrollBar.showButtons", false);
            UIManager.put("ScrollBar.thumbArc", 999);
            UIManager.put("ScrollBar.width", 10);
            UIManager.put("Table.rowHeight", 32);
            UIManager.put("Table.showHorizontalLines", true);
            UIManager.put("Table.showVerticalLines", false);
            UIManager.put("Table.intercellSpacing", new java.awt.Dimension(0, 1));
            UIManager.put("Table.gridColor", new Color(0xF5F5F4));
            UIManager.put("TableHeader.background", CARD);
            UIManager.put("TableHeader.separatorColor", CARD);
            UIManager.put("TableHeader.bottomSeparatorColor", LINE);
            UIManager.put("TableHeader.height", 32);
        } catch (Exception | LinkageError e) {
            // Without the theme the app still works with Java's default look.
        }
    }

    static Font font(int style, float size) {
        Font base = UIManager.getFont("defaultFont");
        if (base == null) {
            base = UIManager.getFont("Label.font");
        }
        return base.deriveFont(style, size);
    }

    static JLabel label(String text, int style, float size, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(font(style, size));
        l.setForeground(color);
        return l;
    }

    /** A rounded white card with a light border, like the dashboard's cards. */
    static JPanel card(java.awt.LayoutManager layout) {
        JPanel p = new JPanel(layout);
        p.putClientProperty(FlatClientProperties.STYLE, "arc: 16");
        p.setBackground(CARD);
        p.setBorder(roundedBorder(LINE, new Insets(14, 16, 14, 16)));
        return p;
    }

    static Border roundedBorder(Color color, Insets padding) {
        try {
            return new FlatLineBorder(padding, color, 1f, 16);
        } catch (LinkageError e) {
            return BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(color),
                    BorderFactory.createEmptyBorder(padding.top, padding.left, padding.bottom, padding.right));
        }
    }

    /** The dark, filled button for the main action. */
    static JButton primary(String text) {
        JButton b = new JButton(text);
        b.putClientProperty(FlatClientProperties.STYLE,
                "background: #1C1917; foreground: #FFFFFF; hoverBackground: #44403C; pressedBackground: #0C0A09;"
                        + " borderWidth: 0; focusWidth: 0; arc: 10; margin: 7,16,7,16");
        b.setFont(font(Font.BOLD, 13f));
        return b;
    }

    static JButton secondary(String text) {
        JButton b = new JButton(text);
        b.putClientProperty(FlatClientProperties.STYLE,
                "arc: 10; margin: 7,14,7,14; borderColor: #D6D3D1; focusWidth: 0; focusedBorderColor: #78716C");
        b.setFont(font(Font.PLAIN, 13f));
        return b;
    }

    static void style(JComponent c, String flatStyle) {
        c.putClientProperty(FlatClientProperties.STYLE, flatStyle);
    }

    static ImageIcon logo(int size) {
        java.net.URL url = UiTheme.class.getResource(size > 64 ? "logo-256.png" : "logo-64.png");
        ImageIcon icon = new ImageIcon(Objects.requireNonNull(url, "logo missing"));
        return new ImageIcon(icon.getImage().getScaledInstance(size, size, Image.SCALE_SMOOTH));
    }

    static List<Image> windowIcons() {
        return List.of(logo(16).getImage(), logo(32).getImage(), logo(64).getImage(), logo(256).getImage());
    }
}
