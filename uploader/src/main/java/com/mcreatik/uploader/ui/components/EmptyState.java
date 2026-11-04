package com.mcreatik.uploader.ui.components;

import java.awt.Component;
import java.awt.Font;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.mcreatik.uploader.ui.theme.Icons;

/** Icon, title and one helpful line, for lists with nothing to show yet (or that failed to load). */
public final class EmptyState extends JPanel {
    private final JLabel icon = new JLabel();
    private final Ui.TLabel title = Ui.label(" ", Font.BOLD, 14f, "text");
    private final Ui.TLabel hint = Ui.label(" ", Font.PLAIN, 12.5f, "muted");

    public EmptyState(String iconName, String titleText, String hintText) {
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        add(Box.createVerticalGlue());
        for (JLabel l : new JLabel[] {icon, title, hint}) {
            l.setAlignmentX(Component.CENTER_ALIGNMENT);
        }
        add(icon);
        add(Box.createVerticalStrut(10));
        add(title);
        add(Box.createVerticalStrut(4));
        add(hint);
        add(Box.createVerticalGlue());
        set(iconName, "faint", titleText, hintText);
    }

    public void set(String iconName, String iconToken, String titleText, String hintText) {
        icon.setIcon(Icons.get(iconName, 30, iconToken));
        title.text(titleText);
        hint.text(hintText);
    }
}
