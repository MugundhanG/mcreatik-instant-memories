package com.mcreatik.uploader.ui.components;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

import com.mcreatik.uploader.queue.QueueItem;
import com.mcreatik.uploader.ui.LiveState;
import com.mcreatik.uploader.ui.LiveState.Tone;
import com.mcreatik.uploader.ui.PhotoInfo;
import com.mcreatik.uploader.ui.theme.Icons;
import com.mcreatik.uploader.ui.theme.Tok;

/**
 * Photos as rows: thumbnail (from the file on this computer), filename, size and time, and the upload status. Rows
 * are rebuilt only when the queue changes, so the 500 ms refresh is cheap.
 */
public final class PhotoList extends JPanel {
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss");
    private List<QueueItem> shown = List.of();
    private final List<Row> rows = new ArrayList<>();

    public PhotoList() {
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    }

    public boolean isEmpty() {
        return shown.isEmpty();
    }

    public void set(List<QueueItem> items) {
        if (items.equals(shown)) {
            return;
        }
        shown = items;
        while (rows.size() < items.size()) {
            Row r = new Row();
            rows.add(r);
            add(r);
        }
        while (rows.size() > items.size()) {
            remove(rows.remove(rows.size() - 1));
        }
        for (int i = 0; i < items.size(); i++) {
            rows.get(i).set(items.get(i), i == items.size() - 1);
        }
        revalidate();
        repaint();
    }

    static Tone tone(QueueItem item) {
        return switch (item.status()) {
            case DONE -> Tone.GREEN;
            case UPLOADING -> Tone.BLUE;
            case QUEUED -> item.attempts() > 0 ? Tone.AMBER : Tone.GREY;
            case DUPLICATE -> Tone.GREY;
            case FAILED -> Tone.RED;
        };
    }

    static String label(QueueItem item) {
        return switch (item.status()) {
            case DONE -> "Uploaded";
            case UPLOADING -> "Uploading";
            case QUEUED -> item.attempts() > 0 ? "Retrying" : "Waiting";
            case DUPLICATE -> "Already sent";
            case FAILED -> "Failed";
        };
    }

    private final class Row extends JPanel {
        private final Thumb thumb = new Thumb();
        private final Ui.TLabel name = Ui.label(" ", Font.BOLD, 13f, "text");
        private final Ui.TLabel meta = Ui.label(" ", Font.PLAIN, 12f, "muted");
        private final Pill pill = new Pill();
        private boolean last;

        Row() {
            super(new BorderLayout(12, 0));
            setOpaque(false);
            setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 4, 8, 4));
            add(thumb, BorderLayout.WEST);
            JPanel text = Ui.clear(new java.awt.GridLayout(2, 1, 0, 2));
            text.add(name);
            text.add(meta);
            add(text, BorderLayout.CENTER);
            JPanel right = Ui.clear(new java.awt.GridBagLayout());
            right.add(pill);
            add(right, BorderLayout.EAST);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
            setAlignmentX(LEFT_ALIGNMENT);
        }

        void set(QueueItem item, boolean isLast) {
            last = isLast;
            name.text(item.fileName());
            String when = time.format(new Date(item.status() == com.mcreatik.uploader.queue.QueueStatus.QUEUED
                    ? item.createdAt() : item.updatedAt()));
            boolean problem = item.lastError() != null && (item.status() == com.mcreatik.uploader.queue.QueueStatus.FAILED
                    || item.status() == com.mcreatik.uploader.queue.QueueStatus.QUEUED);
            meta.text(problem ? item.lastError() : LiveState.size(item.size()) + "  ·  " + when);
            meta.tint(problem ? (item.status() == com.mcreatik.uploader.queue.QueueStatus.FAILED ? "red" : "amber") : "muted");
            meta.setToolTipText(problem ? item.lastError() : null);
            pill.set(tone(item), label(item));
            thumb.set(item);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (!last) {
                g.setColor(Tok.c("border"));
                g.fillRect(4, getHeight() - 1, getWidth() - 8, 1);
            }
        }
    }

    /** Rounded square thumbnail; a photo icon while loading or if the file can't be read. */
    private static final class Thumb extends JComponent {
        private static final int SIZE = 46;
        private QueueItem item;

        Thumb() {
            setPreferredSize(new Dimension(SIZE, SIZE));
        }

        void set(QueueItem i) {
            item = i;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            RoundRectangle2D shape = new RoundRectangle2D.Float(0, 0, SIZE, SIZE, 10, 10);
            g2.setColor(Tok.c("inset"));
            g2.fill(shape);
            BufferedImage img = item == null ? null : PhotoInfo.thumbnail(item.path(), this::repaint);
            if (img != null) {
                g2.clip(shape);
                g2.drawImage(img, 0, 0, SIZE, SIZE, null);
            } else {
                boolean broken = item != null && PhotoInfo.unreadable(item.path());
                Icons.get(broken ? "triangle-alert" : "image", 18, "faint").paintIcon(this, g2, (SIZE - 18) / 2, (SIZE - 18) / 2);
            }
            g2.setClip(null);
            g2.setColor(Tok.c("border"));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, SIZE - 1, SIZE - 1, 10, 10));
            g2.dispose();
        }
    }
}
