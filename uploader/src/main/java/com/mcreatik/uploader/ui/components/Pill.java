package com.mcreatik.uploader.ui.components;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

import javax.swing.JComponent;

import com.mcreatik.uploader.ui.LiveState.Tone;
import com.mcreatik.uploader.ui.theme.Tok;

/** A rounded status chip with a dot, tinted by tone: "● Uploaded", "● Live". */
public final class Pill extends Ui.Surface {
    private final Dot dot = new Dot();
    private final Ui.TLabel text = Ui.label(" ", Font.BOLD, 11.5f, "muted");
    private Tone tone;

    public Pill() {
        super(new BorderLayout(6, 0), "greySoft", "greyLine", 999, new Insets(3, 9, 3, 10));
        add(dot, BorderLayout.WEST);
        add(text, BorderLayout.CENTER);
        set(Tone.GREY, " ");
    }

    public Pill set(Tone t, String label) {
        if (t != tone) {
            tone = t;
            tint(t.token + "Soft", t.token + "Line");
            text.tint(t.token);
            dot.repaint();
        }
        text.text(label);
        return this;
    }

    private final class Dot extends JComponent {
        Dot() {
            setPreferredSize(new java.awt.Dimension(7, 7));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Tok.c(tone == null ? "grey" : tone == Tone.GREEN ? "greenStrong" : tone.token));
            int y = (getHeight() - 7) / 2;
            g2.fillOval(0, y, 7, 7);
            g2.dispose();
        }
    }
}
