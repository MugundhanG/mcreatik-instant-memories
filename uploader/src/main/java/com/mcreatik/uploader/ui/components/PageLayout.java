package com.mcreatik.uploader.ui.components;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Rectangle;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;

import com.mcreatik.uploader.ui.theme.Tok;

/** A page: title and subtitle, then sections stacked with even spacing, scrolling vertically when needed. */
public final class PageLayout extends JPanel {
    private final Stack stack = new Stack();

    public PageLayout(String title, String subtitle) {
        super(new BorderLayout());
        stack.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        if (title != null) {
            stack.add(Ui.label(title, Font.BOLD, 20f, "text"));
            stack.add(Box.createVerticalStrut(2));
            stack.add(Ui.label(subtitle, Font.PLAIN, 13f, "muted"));
            stack.add(Box.createVerticalStrut(18));
        }
        JScrollPane scroll = new JScrollPane(stack);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        // repaint while scrolling (the fast "blit" mode leaves marks with see-through cards)
        scroll.getViewport().setScrollMode(javax.swing.JViewport.SIMPLE_SCROLL_MODE);
        add(scroll, BorderLayout.CENTER);
    }

    private boolean filled;
    private final Component tail = Box.createVerticalGlue();

    /** Adds a full-width section at its natural height, with the standard gap above it. */
    public <T extends JComponent> T section(T c) {
        add(new Natural(c));
        return c;
    }

    /** A section that takes the remaining height (the photo list). */
    public void fill(JComponent c) {
        filled = true;
        stack.remove(tail);
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        gap();
        stack.add(c);
    }

    private void add(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        stack.remove(tail);
        gap();
        stack.add(c);
        if (!filled) {
            stack.add(tail); // short pages keep their cards at natural height, with space below
        }
    }

    private void gap() {
        int n = stack.getComponentCount();
        if (n > 0 && !(stack.getComponent(n - 1) instanceof Box.Filler)) {
            stack.add(Box.createVerticalStrut(14));
        }
    }

    /** Holds a section at its preferred height, whatever height the page has. */
    private static final class Natural extends JPanel {
        Natural(JComponent c) {
            super(new BorderLayout());
            setOpaque(false);
            add(c, BorderLayout.CENTER);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(Tok.c("bg"));
        g.fillRect(0, 0, getWidth(), getHeight());
    }

    private static final class Stack extends JPanel implements Scrollable {
        Stack() {
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
            return r.height - 48;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            // fill the window when the content is shorter than it (the photo list grows), scroll when taller
            return getParent() != null && getParent().getHeight() > getPreferredSize().height;
        }
    }
}
