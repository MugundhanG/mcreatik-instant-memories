package com.mcreatik.uploader.ui.components;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.mcreatik.uploader.ui.LiveState;
import com.mcreatik.uploader.ui.LiveState.Tone;
import com.mcreatik.uploader.ui.theme.Icons;
import com.mcreatik.uploader.ui.theme.Tok;

/** Camera → Computer → Upload server → Live gallery, each stage coloured by its real state. */
public final class PipelineView extends Ui.Surface {
    private final StageTile[] tiles = new StageTile[4];
    private final Connector[] links = new Connector[3];

    public PipelineView() {
        super(new BorderLayout(0, 12), "card", "border", Ui.ARC, new Insets(14, 16, 16, 16));
        JPanel head = Ui.clear(new BorderLayout());
        head.add(Ui.label("Live upload pipeline", Font.BOLD, 14f, "text"), BorderLayout.WEST);
        head.add(Ui.label("Where each photo is right now", Font.PLAIN, 12f, "muted"), BorderLayout.EAST);
        add(head, BorderLayout.NORTH);
        JPanel flow = Ui.clear(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.BOTH;
        g.weighty = 1;
        for (int i = 0; i < 4; i++) {
            tiles[i] = new StageTile();
            g.weightx = 1;
            flow.add(tiles[i], g);
            if (i < 3) {
                links[i] = new Connector();
                g.weightx = 0;
                flow.add(links[i], g);
            }
        }
        add(flow, BorderLayout.CENTER);
    }

    public void set(List<LiveState.Stage> stages) {
        for (int i = 0; i < 4; i++) {
            tiles[i].set(stages.get(i));
        }
        for (int i = 0; i < 3; i++) {
            // the arrow shows photos moving on: coloured only when the next stage has them too
            Tone from = stages.get(i).tone();
            Tone to = stages.get(i + 1).tone();
            boolean flowing = from == Tone.GREEN || from == Tone.BLUE;
            links[i].set(!flowing ? "greyLine" : to == Tone.GREEN ? "greenStrong" : to == Tone.BLUE ? "blue" : "greyLine");
        }
    }

    private static final class StageTile extends Ui.Surface {
        private final Ui.Surface circle = new Ui.Surface(new BorderLayout(), "greySoft", null, 999, new Insets(9, 9, 9, 9));
        private final JLabel icon = new JLabel();
        private final Ui.TLabel name = Ui.label(" ", Font.BOLD, 13f, "text");
        private final Pill status = new Pill();
        private final Ui.TLabel detail = Ui.label(" ", Font.PLAIN, 11.5f, "muted");
        private String iconName;

        StageTile() {
            super(new BorderLayout(10, 0), "inset", "border", 12, new Insets(10, 12, 10, 12));
            circle.add(icon, BorderLayout.CENTER);
            JPanel left = Ui.clear(new GridBagLayout());
            left.add(circle);
            add(left, BorderLayout.WEST);
            JPanel text = Ui.clear(null);
            text.setLayout(new javax.swing.BoxLayout(text, javax.swing.BoxLayout.Y_AXIS));
            JPanel pillRow = Ui.clear(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
            pillRow.add(status);
            for (JComponent c : new JComponent[] {name, pillRow, detail}) {
                c.setAlignmentX(LEFT_ALIGNMENT);
            }
            text.add(name);
            text.add(javax.swing.Box.createVerticalStrut(4));
            text.add(pillRow);
            text.add(javax.swing.Box.createVerticalStrut(4));
            text.add(detail);
            add(text, BorderLayout.CENTER);
            setMinimumSize(new Dimension(150, 70));
            setPreferredSize(new Dimension(150, 88)); // equal widths: the grid shares extra space evenly
        }

        void set(LiveState.Stage s) {
            if (!s.icon().equals(iconName)) {
                iconName = s.icon();
                icon.setIcon(Icons.get(s.icon(), 20, s.tone().token));
            }
            ((Icons.Icon) icon.getIcon()).tint(s.tone() == Tone.GREEN ? "greenStrong" : s.tone().token);
            circle.tint(s.tone().token + "Soft", null);
            tint(s.tone() == Tone.RED ? "redSoft" : "inset", s.tone() == Tone.RED ? "redLine" : "border");
            name.text(s.name());
            status.set(s.tone(), s.status());
            detail.text(s.detail());
            detail.setToolTipText(s.detail());
            repaint();
        }
    }

    /** The arrow between two stages: coloured when a photo has passed through. */
    private static final class Connector extends JComponent {
        private String token = "greyLine";

        Connector() {
            setPreferredSize(new Dimension(34, 10));
            setMinimumSize(new Dimension(18, 10));
        }

        void set(String t) {
            if (!t.equals(token)) {
                token = t;
                repaint();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Tok.c(token));
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int y = getHeight() / 2;
            int x1 = 4;
            int x2 = getWidth() - 5;
            g2.drawLine(x1, y, x2, y);
            g2.drawLine(x2 - 5, y - 5, x2, y);
            g2.drawLine(x2 - 5, y + 5, x2, y);
            g2.dispose();
        }
    }
}
