package com.mcreatik.uploader.ui.components;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.Timer;

import com.mcreatik.uploader.ui.theme.Icons;

/** A turning "loader" icon for a button that is waiting on the network; turns only while shown. */
public final class SpinnerIcon implements Icon {
    private final Icon base = Icons.get("loader-circle", 16, "muted");
    private double angle;
    private Timer timer;

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        if (timer == null && c instanceof JComponent jc) {
            timer = new Timer(50, e -> {
                if (!jc.isShowing()) {
                    timer.stop();
                    timer = null;
                    return;
                }
                angle += Math.PI / 8;
                jc.repaint();
            });
            timer.start();
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.rotate(angle, x + getIconWidth() / 2.0, y + getIconHeight() / 2.0);
        base.paintIcon(c, g2, x, y);
        g2.dispose();
    }

    @Override
    public int getIconWidth() {
        return 16;
    }

    @Override
    public int getIconHeight() {
        return 16;
    }
}
