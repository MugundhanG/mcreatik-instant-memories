package com.mcreatik.uploader.ui.components;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import javax.swing.JPanel;

import com.mcreatik.uploader.CameraRuntime;
import com.mcreatik.uploader.ui.LiveState;

/**
 * The cameras of this laptop as tabs (status dot, name, port, photos uploaded), then "+ Add camera". Clicking a
 * tab shows that camera below. For the selected camera: Pause / Resume, Rename, and Remove (never Camera 1).
 */
public final class CameraStrip extends JPanel {

    /** One camera's line in the strip. */
    public record Entry(CameraRuntime camera, String name, LiveState state, int port) {
    }

    private final JPanel tabs = Ui.clear(null);
    private final Ui.Btn add = new Ui.Btn("Add camera", Ui.Kind.SECONDARY, "camera");
    private final Ui.Btn remove = new Ui.Btn("Remove camera", Ui.Kind.GHOST, null);
    private final Ui.Btn pause = new Ui.Btn("Pause", Ui.Kind.SECONDARY, "circle-pause");
    private final Ui.Btn rename = new Ui.Btn("Rename", Ui.Kind.GHOST, "pencil");
    private final Consumer<CameraRuntime> onSelect;
    private final List<Tab> shown = new ArrayList<>();
    private CameraRuntime selected;

    public CameraStrip(Consumer<CameraRuntime> onSelect, Runnable onAdd, Consumer<CameraRuntime> onRemove,
                       Consumer<CameraRuntime> onPause, Consumer<CameraRuntime> onRename) {
        super(new BorderLayout(12, 0));
        setOpaque(false);
        this.onSelect = onSelect;
        add.setText("+ Add camera");
        add.setIcon(null);
        add.addActionListener(e -> onAdd.run());
        remove.addActionListener(e -> {
            if (selected != null) {
                onRemove.accept(selected);
            }
        });
        pause.addActionListener(e -> {
            if (selected != null) {
                onPause.accept(selected);
            }
        });
        rename.addActionListener(e -> {
            if (selected != null) {
                onRename.accept(selected);
            }
        });
        remove.setForeground(com.mcreatik.uploader.ui.theme.Tok.c("red"));
        // one row that never wraps: tabs shrink (down to their minimum) when the window is narrow
        tabs.setLayout(new javax.swing.BoxLayout(tabs, javax.swing.BoxLayout.X_AXIS));
        tabs.add(add);
        add(tabs, BorderLayout.CENTER);
        JPanel right = Ui.clear(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        right.add(pause);
        right.add(rename);
        right.add(remove);
        add(right, BorderLayout.EAST);
    }

    /** Refreshes the tabs; rebuilds them only when the cameras changed. */
    public void set(List<Entry> entries, CameraRuntime selectedCamera, boolean canAdd, String addHint) {
        selected = selectedCamera;
        boolean same = entries.size() == shown.size();
        for (int i = 0; same && i < entries.size(); i++) {
            same = shown.get(i).camera == entries.get(i).camera();
        }
        if (!same) {
            tabs.removeAll();
            shown.clear();
            for (Entry e : entries) {
                Tab t = new Tab(e.camera());
                shown.add(t);
                tabs.add(t);
                tabs.add(javax.swing.Box.createHorizontalStrut(8));
            }
            tabs.add(add);
            tabs.add(javax.swing.Box.createHorizontalGlue());
            tabs.revalidate();
            tabs.repaint();
        }
        for (int i = 0; i < entries.size(); i++) {
            shown.get(i).set(entries.get(i), entries.get(i).camera() == selectedCamera);
        }
        add.setVisible(entries.size() < com.mcreatik.uploader.config.CameraSet.MAX);
        add.setEnabled(canAdd);
        add.setToolTipText(addHint);
        remove.setVisible(selectedCamera != null && selectedCamera.number() != 1);
        boolean isPaused = selectedCamera != null && selectedCamera.engine().isPaused();
        pause.setText(isPaused ? "Resume" : "Pause");
        pause.setIcon(com.mcreatik.uploader.ui.theme.Icons.get(isPaused ? "circle-play" : "circle-pause", 15, "text"));
        pause.setToolTipText(isPaused ? "Upload new photos from this camera again"
                : "Keep this camera's new photos on this laptop (private moments). Nothing goes to the gallery.");
        pause.setVisible(selectedCamera != null);
        rename.setVisible(selectedCamera != null);
        remove.setForeground(com.mcreatik.uploader.ui.theme.Tok.c("red"));
    }

    private final class Tab extends Ui.Surface {
        private final CameraRuntime camera;
        private final Dot dot = new Dot();
        private final Ui.TLabel name = Ui.label(" ", Font.BOLD, 13f, "text");
        private final Ui.TLabel meta = Ui.label(" ", Font.PLAIN, 11.5f, "muted");

        Tab(CameraRuntime camera) {
            super(new BorderLayout(10, 0), "card", "border", 12, new Insets(7, 12, 7, 14));
            this.camera = camera;
            JPanel text = Ui.clear(new java.awt.GridLayout(2, 1, 0, 1));
            text.add(name);
            text.add(meta);
            JPanel dotWrap = Ui.clear(new java.awt.GridBagLayout());
            dotWrap.add(dot);
            add(dotWrap, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
            setMinimumSize(new java.awt.Dimension(130, 46));
            setPreferredSize(new java.awt.Dimension(200, 46));
            setMaximumSize(new java.awt.Dimension(200, 46));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFocusable(true);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    onSelect.accept(camera);
                }
            });
            addKeyListener(new java.awt.event.KeyAdapter() {
                @Override
                public void keyPressed(java.awt.event.KeyEvent e) {
                    if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER || e.getKeyCode() == java.awt.event.KeyEvent.VK_SPACE) {
                        onSelect.accept(camera);
                    }
                }
            });
        }

        void set(Entry e, boolean isSelected) {
            name.text(e.name());
            String word = switch (e.state().conn()) {
                case ONLINE -> "Online";
                case PAUSED -> "Paused";
                case RECONNECTING -> "Reconnecting";
                case OFFLINE -> "Offline";
                case ERROR -> "Needs attention";
            };
            dot.tone = e.state().tone();
            dot.repaint();
            setToolTipText(e.name() + " · " + word + (e.port() > 0 ? " · Port " + e.port() : ""));
            meta.text((e.port() > 0 ? "Port " + e.port() + "  ·  " : "")
                    + String.format(Locale.ROOT, "%,d uploaded", e.state().uploaded()));
            tint(isSelected ? "selected" : "card", isSelected ? "greenStrong" : "border");
            getAccessibleContext().setAccessibleName(e.name() + ", " + word);
        }
    }

    /** A status dot coloured by the camera's state (green, blue, amber, red). */
    private static final class Dot extends JPanel {
        LiveState.Tone tone = LiveState.Tone.GREY;

        Dot() {
            setOpaque(false);
            setPreferredSize(new java.awt.Dimension(10, 10));
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(com.mcreatik.uploader.ui.theme.Tok.c(tone == LiveState.Tone.GREEN ? "greenStrong" : tone.token));
            g2.fillOval(0, 0, 10, 10);
            g2.dispose();
        }
    }
}
