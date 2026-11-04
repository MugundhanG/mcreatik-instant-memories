package com.mcreatik.uploader.ui.components;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * Two (or more) columns at fixed shares of the width, all as tall as the tallest, whatever their contents prefer.
 * Used for the status card beside the camera Wi-Fi card.
 */
public final class RatioRow implements LayoutManager {
    private final double[] shares;
    private final int gap;

    public RatioRow(int gap, double... shares) {
        this.gap = gap;
        this.shares = shares;
    }

    private int[] widths(Container parent) {
        Insets in = parent.getInsets();
        int n = parent.getComponentCount();
        int avail = parent.getWidth() - in.left - in.right - gap * Math.max(0, n - 1);
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += shares[Math.min(i, shares.length - 1)];
        }
        int[] w = new int[n];
        int used = 0;
        for (int i = 0; i < n; i++) {
            w[i] = i == n - 1 ? avail - used : (int) Math.round(avail * shares[Math.min(i, shares.length - 1)] / sum);
            used += w[i];
        }
        return w;
    }

    @Override
    public void layoutContainer(Container parent) {
        Insets in = parent.getInsets();
        int[] w = widths(parent);
        int h = parent.getHeight() - in.top - in.bottom;
        int x = in.left;
        for (int i = 0; i < parent.getComponentCount(); i++) {
            parent.getComponent(i).setBounds(x, in.top, w[i], h);
            x += w[i] + gap;
        }
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        Insets in = parent.getInsets();
        int h = 0;
        int[] w = parent.getWidth() > 0 ? widths(parent) : null;
        for (int i = 0; i < parent.getComponentCount(); i++) {
            Component c = parent.getComponent(i);
            if (w != null) {
                c.setSize(w[i], Short.MAX_VALUE); // let width-dependent layouts (wrapping grids) settle first
            }
            h = Math.max(h, c.getPreferredSize().height);
        }
        return new Dimension(600, h + in.top + in.bottom);
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        return preferredLayoutSize(parent);
    }

    @Override
    public void addLayoutComponent(String name, Component comp) {
    }

    @Override
    public void removeLayoutComponent(Component comp) {
    }
}
