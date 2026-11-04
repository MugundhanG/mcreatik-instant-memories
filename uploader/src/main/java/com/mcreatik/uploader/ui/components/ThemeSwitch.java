package com.mcreatik.uploader.ui.components;

import java.awt.Cursor;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;

import javax.swing.ButtonGroup;
import javax.swing.JToggleButton;

import com.formdev.flatlaf.FlatClientProperties;
import com.mcreatik.uploader.ui.theme.Icons;
import com.mcreatik.uploader.ui.theme.Theme;

/** Segmented Light | Dark switch. Switching is instant and remembered. */
public final class ThemeSwitch extends Ui.Surface {
    private final JToggleButton light = segment(Theme.LIGHT, "sun");
    private final JToggleButton dark = segment(Theme.DARK, "moon");

    public ThemeSwitch() {
        super(new GridLayout(1, 2, 2, 0), "inset", "border", 12, new Insets(3, 3, 3, 3));
        ButtonGroup group = new ButtonGroup();
        group.add(light);
        group.add(dark);
        add(light);
        add(dark);
        sync();
        Theme.onChange(this::sync);
        getAccessibleContext().setAccessibleName("Theme");
    }

    private void sync() {
        (Theme.current().dark() ? dark : light).setSelected(true);
    }

    private JToggleButton segment(Theme t, String icon) {
        JToggleButton b = new JToggleButton(t.label, Icons.get(icon, 14, "text")) {
            @Override
            public void updateUI() {
                putClientProperty(FlatClientProperties.STYLE, "arc: 9; margin: 4,10,4,12; borderWidth: 0; focusWidth: 0;"
                        + " background: " + Ui.hex("inset") + "; selectedBackground: " + Ui.hex("segment")
                        + "; hoverBackground: " + Ui.hex("hover") + "; foreground: " + Ui.hex("muted")
                        + "; selectedForeground: " + Ui.hex("text"));
                super.updateUI();
                setFont(Ui.font(Font.BOLD, 12f));
            }
        };
        b.setIconTextGap(6);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setToolTipText(t.label + " mode");
        b.addActionListener(e -> Theme.switchTo(t));
        return b;
    }
}
