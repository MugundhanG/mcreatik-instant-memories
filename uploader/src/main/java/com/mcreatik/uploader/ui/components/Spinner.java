package com.mcreatik.uploader.ui.components;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.JComponent;
import javax.swing.Timer;

import com.mcreatik.uploader.ui.theme.Tok;

/** A small turning arc for "Connecting…" and "Reconnecting…". Stops turning when hidden. */
public final class Spinner extends JComponent {
    private final int size;
    private String token;
    private int angle;
    private final Timer timer = new Timer(60, e -> {
        angle = (angle + 24) % 360;
        repaint();
    });

    public Spinner(int size, String token) {
        this.size = size;
        this.token = token;
        setPreferredSize(new Dimension(size, size));
        setMinimumSize(getPreferredSize());
    }

    public void tint(String t) {
        token = t;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        timer.start();
    }

    @Override
    public void removeNotify() {
        timer.stop();
        super.removeNotify();
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (!isShowing()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float stroke = Math.max(2f, size / 9f);
        g2.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int x = (getWidth() - size) / 2 + (int) stroke;
        int y = (getHeight() - size) / 2 + (int) stroke;
        int d = size - 2 * (int) stroke;
        g2.setColor(Tok.c("greyLine"));
        g2.drawOval(x, y, d, d);
        g2.setColor(Tok.c(token));
        g2.drawArc(x, y, d, d, -angle, 100);
        g2.dispose();
    }
}
