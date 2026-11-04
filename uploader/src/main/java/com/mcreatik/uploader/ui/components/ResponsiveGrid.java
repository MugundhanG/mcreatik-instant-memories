package com.mcreatik.uploader.ui.components;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * Equal-width cells in as many columns as fit (each at least {@code minCell} wide), for example 5 metric cards in one
 * row on a wide window and 3 + 2 on a narrow one.
 */
public final class ResponsiveGrid implements LayoutManager {
    private final int minCell;
    private final int gap;

    public ResponsiveGrid(int minCell, int gap) {
        this.minCell = minCell;
        this.gap = gap;
    }

    int columns(Container parent, int width) {
        int n = Math.max(1, parent.getComponentCount());
        int fit = Math.max(1, (width + gap) / (minCell + gap));
        if (fit >= n) {
            return n;
        }
        return Math.max(1, Math.min(fit, (n + 1) / 2));
    }

    @Override
    public void layoutContainer(Container parent) {
        Insets in = parent.getInsets();
        int width = parent.getWidth() - in.left - in.right;
        int cols = columns(parent, width);
        int cellW = (width - gap * (cols - 1)) / cols;
        int rowH = rowHeight(parent);
        for (int i = 0; i < parent.getComponentCount(); i++) {
            int r = i / cols;
            int c = i % cols;
            parent.getComponent(i).setBounds(in.left + c * (cellW + gap), in.top + r * (rowH + gap), cellW, rowH);
        }
    }

    private int rowHeight(Container parent) {
        int h = 0;
        for (Component c : parent.getComponents()) {
            h = Math.max(h, c.getPreferredSize().height);
        }
        return h;
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        Insets in = parent.getInsets();
        int width = parent.getWidth() > 0 ? parent.getWidth() - in.left - in.right : 900;
        int cols = columns(parent, width);
        int rows = (parent.getComponentCount() + cols - 1) / cols;
        return new Dimension(cols * minCell, in.top + in.bottom + rows * rowHeight(parent) + gap * Math.max(0, rows - 1));
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
