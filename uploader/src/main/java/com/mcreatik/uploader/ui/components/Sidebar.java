package com.mcreatik.uploader.ui.components;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.mcreatik.uploader.ui.theme.Icons;
import com.mcreatik.uploader.ui.theme.Tok;

/** Compact left navigation: icon + name, the current page highlighted, a quiet hover. Keyboard: Tab + Enter/Space. */
public final class Sidebar extends JPanel {
    public record Item(String id, String label, String icon) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final JPanel footer = Ui.clear(new BorderLayout());
    private String selected;

    public Sidebar(List<Item> items, Consumer<String> onSelect) {
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(196, 10));
        JPanel list = Ui.clear(null);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(BorderFactory.createEmptyBorder(14, 10, 10, 10));
        list.add(Ui.pad(Ui.caps("Menu"), 0, 10, 8, 0));
        for (Item item : items) {
            Entry e = new Entry(item, onSelect);
            entries.add(e);
            list.add(e);
            list.add(Box.createVerticalStrut(2));
        }
        add(list, BorderLayout.NORTH);
        footer.setBorder(BorderFactory.createEmptyBorder(10, 20, 16, 14));
        add(footer, BorderLayout.SOUTH);
    }

    /** Small status/version block at the bottom of the sidebar. */
    public JPanel footer() {
        return footer;
    }

    public void select(String id) {
        selected = id;
        entries.forEach(Entry::refresh);
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(Tok.c("sidebar"));
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(Tok.c("border"));
        g.fillRect(getWidth() - 1, 0, 1, getHeight());
    }

    private final class Entry extends JPanel {
        private final Item item;
        private final Icons.Icon icon;
        private final Ui.TLabel text;
        private boolean hover;

        Entry(Item item, Consumer<String> onSelect) {
            super(new BorderLayout(10, 0));
            this.item = item;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
            icon = Icons.get(item.icon(), 17, "muted");
            add(new JLabel(icon), BorderLayout.WEST);
            text = Ui.label(item.label(), Font.PLAIN, 13.5f, "muted");
            add(text, BorderLayout.CENTER);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            setAlignmentX(LEFT_ALIGNMENT);
            setFocusable(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            getAccessibleContext().setAccessibleName(item.label());
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    onSelect.accept(item.id());
                }
            });
            addKeyListener(new java.awt.event.KeyAdapter() {
                @Override
                public void keyPressed(java.awt.event.KeyEvent e) {
                    if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER || e.getKeyCode() == java.awt.event.KeyEvent.VK_SPACE) {
                        onSelect.accept(item.id());
                    }
                }
            });
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent e) {
                    repaint();
                }

                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    repaint();
                }
            });
        }

        void refresh() {
            boolean on = item.id().equals(selected);
            icon.tint(on ? "greenStrong" : "muted");
            text.tint(on ? "text" : "muted");
            text.setFont(Ui.font(on ? Font.BOLD : Font.PLAIN, 13.5f));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            boolean on = item.id().equals(selected);
            if (on || hover || isFocusOwner()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Tok.c(on ? "selected" : "hover"));
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 10, 10));
                if (on) {
                    g2.setColor(Tok.c("border"));
                    g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, 10, 10));
                    g2.setColor(Tok.c("greenStrong"));
                    g2.fill(new RoundRectangle2D.Float(0, 9, 3, getHeight() - 18, 3, 3));
                }
                if (isFocusOwner() && !on) {
                    g2.setColor(Tok.c("borderStrong"));
                    g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, 10, 10));
                }
                g2.dispose();
            }
        }
    }
}
