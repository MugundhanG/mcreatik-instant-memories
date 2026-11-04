package com.mcreatik.uploader.ui.components;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Font;
import java.awt.Insets;

import javax.swing.JLabel;
import javax.swing.JPanel;

import com.mcreatik.uploader.ui.LiveState;
import com.mcreatik.uploader.ui.LiveState.Conn;
import com.mcreatik.uploader.ui.theme.Icons;

/**
 * The big connection card: Online (green), Reconnecting (blue, turning), Offline (amber) or Error (red), with the
 * camera, auto-upload, last photo, speed and connection time underneath.
 */
public final class StatusCard extends Ui.Surface {
    private final Ui.Surface badge = new Ui.Surface(new CardLayout(), "greenSoft", null, 999, new Insets(10, 10, 10, 10));
    private final Icons.Icon stateIcon = Icons.get("circle-check", 26, "green");
    private final Spinner spinner = new Spinner(26, "blue");
    private final Ui.TLabel title = Ui.label(" ", Font.BOLD, 18f, "green");
    private final Ui.Wrap detail = new Ui.Wrap("muted", 13f);
    private final Ui.TLabel[] values = new Ui.TLabel[5];
    private Conn shownConn;

    public StatusCard() {
        super(new BorderLayout(0, 14), "greenSoft", "greenLine", Ui.ARC, new Insets(16, 18, 16, 18));
        badge.add(new JLabel(stateIcon), "icon");
        badge.add(spinner, "spin");
        JPanel head = Ui.clear(new BorderLayout(14, 0));
        head.add(badge, BorderLayout.WEST);
        JPanel text = Ui.clear(new BorderLayout(0, 3));
        text.add(title, BorderLayout.NORTH);
        text.add(detail, BorderLayout.CENTER);
        head.add(text, BorderLayout.CENTER);
        add(head, BorderLayout.NORTH);

        String[][] rows = {{"camera", "Camera"}, {"upload", "Auto upload"}, {"clock", "Last photo"},
                {"zap", "Upload speed"}, {"activity", "Connection"}};
        JPanel grid = Ui.clear(new ResponsiveGrid(128, 8));
        for (int i = 0; i < rows.length; i++) {
            Ui.Surface cell = new Ui.Surface(new BorderLayout(0, 3), "card", "border", 10, new Insets(8, 10, 8, 10));
            JLabel name = Ui.label(rows[i][1], Font.PLAIN, 11.5f, "muted");
            name.setIcon(Icons.get(rows[i][0], 13, "muted"));
            name.setIconTextGap(5);
            values[i] = Ui.label("—", Font.BOLD, 13f, "text");
            cell.add(name, BorderLayout.NORTH);
            cell.add(values[i], BorderLayout.CENTER);
            grid.add(cell);
        }
        JPanel holder = Ui.clear(new BorderLayout());
        holder.add(grid, BorderLayout.NORTH); // cells keep their natural height when the card is taller
        add(holder, BorderLayout.CENTER);
    }

    public void set(LiveState s) {
        String t = s.tone().token;
        if (s.conn() != shownConn) {
            shownConn = s.conn();
            ((CardLayout) badge.getLayout()).show(badge, s.conn() == Conn.RECONNECTING ? "spin" : "icon");
            stateIcon.tint(t);
        }
        // icon shape follows the state
        String iconName = switch (s.conn()) {
            case ONLINE -> "circle-check";
            case OFFLINE -> "wifi-off";
            case ERROR -> "circle-alert";
            case RECONNECTING -> "loader-circle";
        };
        JLabel iconLabel = (JLabel) badge.getComponent(0);
        if (!iconName.equals(iconLabel.getName())) {
            iconLabel.setName(iconName);
            iconLabel.setIcon(Icons.get(iconName, 26, t));
        }
        ((Icons.Icon) iconLabel.getIcon()).tint(t);
        spinner.tint(t);
        tint(t + "Soft", t + "Line");
        badge.tint("card", null);
        title.tint(t);
        title.text(s.title());
        detail.text(s.detail());
        String[] v = {s.camera(), s.autoUpload(), s.lastPhoto(), s.speed(), s.duration()};
        for (int i = 0; i < v.length; i++) {
            values[i].text(v[i]);
            values[i].setToolTipText(v[i]);
        }
        repaint();
    }
}
