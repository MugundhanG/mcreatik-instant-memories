package com.mcreatik.uploader.ui.components;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatClientProperties;
import com.mcreatik.uploader.ui.theme.Icons;
import com.mcreatik.uploader.ui.theme.Tok;

/**
 * The building blocks every screen uses. Each reads its colours from the theme tokens when it paints or when the
 * theme changes ({@code updateUI}), so one set of components serves both Light and Dark.
 */
public final class Ui {

    /** Spacing scale (px). */
    public static final int S1 = 4, S2 = 8, S3 = 12, S4 = 16, S5 = 24;
    public static final int ARC = 14;

    private Ui() {
    }

    public static Font font(int style, float size) {
        Font base = UIManager.getFont("defaultFont");
        if (base == null) {
            base = UIManager.getFont("Label.font");
        }
        return base.deriveFont(style, size);
    }

    public static TLabel label(String text, int style, float size, String token) {
        return new TLabel(text, style, size, token);
    }

    /** Small caps heading, for example "LIVE EVENT". */
    public static TLabel caps(String text) {
        return new TLabel(text.toUpperCase(), Font.BOLD, 10.5f, "muted");
    }

    public static JPanel row(int gap) {
        JPanel p = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, gap, 0));
        p.setOpaque(false);
        return p;
    }

    public static JPanel clear(LayoutManager layout) {
        JPanel p = new JPanel(layout);
        p.setOpaque(false);
        return p;
    }

    public static <T extends JComponent> T pad(T c, int top, int left, int bottom, int right) {
        c.setBorder(BorderFactory.createEmptyBorder(top, left, bottom, right));
        return c;
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** A label whose colour is a theme token. */
    public static class TLabel extends JLabel {
        private String token;
        private final int style;
        private final float size;

        public TLabel(String text, int style, float size, String token) {
            super(text);
            this.style = style;
            this.size = size;
            this.token = token;
            updateUI();
        }

        @Override
        public void updateUI() {
            super.updateUI();
            if (token != null) {
                setForeground(Tok.c(token));
                setFont(font(style, size));
            }
        }

        public TLabel tint(String newToken) {
            if (!newToken.equals(token)) {
                token = newToken;
                setForeground(Tok.c(token));
            }
            return this;
        }

        /** Sets text only when it changed, so live refreshes don't cause needless relayouts. */
        public void text(String s) {
            String v = s == null || s.isEmpty() ? " " : s;
            if (!v.equals(getText())) {
                setText(v);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** Text that wraps onto more lines instead of being cut off (status messages, errors). */
    public static class Wrap extends javax.swing.JTextArea {
        private String token;
        private final float size;

        public Wrap(String token, float size) {
            this.token = token;
            this.size = size;
            setEditable(false);
            setFocusable(false);
            setLineWrap(true);
            setWrapStyleWord(true);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder());
            updateUI();
        }

        @Override
        public void updateUI() {
            super.updateUI();
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder());
            if (token != null) {
                setForeground(Tok.c(token));
                setFont(font(Font.PLAIN, size));
            }
        }

        public void text(String s) {
            String v = s == null ? "" : s;
            if (!v.equals(getText())) {
                setText(v);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** A rounded surface (card, inset cell, tinted status box) painted from tokens, with an optional soft shadow. */
    public static class Surface extends JPanel {
        private String fill;
        private String line;
        private final int arc;
        private boolean shadow;

        public Surface(LayoutManager layout, String fill, String line, int arc, Insets padding) {
            super(layout);
            this.fill = fill;
            this.line = line;
            this.arc = arc;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(padding.top, padding.left, padding.bottom, padding.right));
        }

        /** The standard white (Light) or dark grey (Dark) card. */
        public static Surface card(LayoutManager layout) {
            Surface s = new Surface(layout, "card", "border", ARC, new Insets(S4, S4 + 2, S4, S4 + 2));
            s.shadow = true;
            return s;
        }

        /** Adds the soft card shadow. */
        public Surface shadow(boolean on) {
            shadow = on;
            return this;
        }

        public Surface tint(String newFill, String newLine) {
            if (!newFill.equals(fill) || !java.util.Objects.equals(newLine, line)) {
                fill = newFill;
                line = newLine;
                repaint();
            }
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            if (shadow) {
                Color sh = Tok.c("shadow");
                g2.setColor(sh);
                g2.fill(new RoundRectangle2D.Float(0, 1.5f, w, h - 1, arc, arc));
            }
            g2.setColor(Tok.c(fill));
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h - (shadow ? 2 : 0), arc, arc));
            if (line != null) {
                g2.setColor(Tok.c(line));
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1 - (shadow ? 2 : 0), arc, arc));
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    public enum Kind { PRIMARY, SECONDARY, GHOST }

    /** Buttons styled from tokens; restyled on every theme change. */
    public static class Btn extends JButton {
        private final Kind kind;

        public Btn(String text, Kind kind, String icon) {
            super(text);
            this.kind = kind;
            if (icon != null) {
                setIcon(Icons.get(icon, 16, kind == Kind.PRIMARY ? "primaryText" : "text"));
                setIconTextGap(8);
            }
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            updateUI();
        }

        @Override
        public void updateUI() {
            if (kind != null) {
                putClientProperty(FlatClientProperties.STYLE, switch (kind) {
                    case PRIMARY -> "background: " + hex("primary") + "; foreground: " + hex("primaryText")
                            + "; hoverBackground: " + hex("primaryHover") + "; pressedBackground: " + hex("primaryPressed")
                            + "; disabledBackground: " + hex("greySoft") + "; disabledText: " + hex("faint")
                            + "; borderWidth: 0; focusWidth: 0; arc: 10; margin: 9,18,9,18";
                    case SECONDARY -> "background: " + hex("secondary") + "; foreground: " + hex("text")
                            + "; hoverBackground: " + hex("secondaryHover") + "; borderColor: " + hex("secondaryBorder")
                            + "; disabledText: " + hex("faint") + "; focusWidth: 0; arc: 10; margin: 8,14,8,14";
                    case GHOST -> "buttonType: borderless; foreground: " + hex("text") + "; hoverBackground: "
                            + hex("hover") + "; arc: 8; margin: 4,8,4,8";
                });
            }
            super.updateUI();
            setFont(font(kind == Kind.PRIMARY ? Font.BOLD : Font.PLAIN, 13f));
        }
    }

    public static String hex(String token) {
        Color c = Tok.c(token);
        return String.format("#%02x%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha());
    }

    /** Paints a component at reduced opacity (used for disabled-looking rows). */
    public static void fade(Graphics2D g, float alpha) {
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
    }
}
