package com.mcreatik.uploader.ui.components;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridLayout;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.mcreatik.uploader.ui.LiveState.Tone;
import com.mcreatik.uploader.ui.theme.Tok;

/** Logo and name on the left; the live event status and the theme switch on the right. */
public final class HeaderBar extends JPanel {
    private final Pill eventPill = new Pill();

    public HeaderBar(Icon logo) {
        super(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 18, 10, 18));
        JPanel brand = Ui.clear(new FlowLayout(FlowLayout.LEFT, 10, 0));
        brand.add(new JLabel(logo));
        JPanel names = Ui.clear(new GridLayout(2, 1));
        names.add(Ui.label("McreatiK", Font.BOLD, 15f, "text"));
        names.add(Ui.label("INSTANT MEMORIES UPLOADER", Font.BOLD, 9.5f, "brand"));
        brand.add(names);
        add(brand, BorderLayout.WEST);
        JPanel right = Ui.clear(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        JPanel pillWrap = Ui.clear(new java.awt.GridBagLayout());
        pillWrap.add(eventPill);
        right.add(pillWrap);
        right.add(new ThemeSwitch());
        add(right, BorderLayout.EAST);
    }

    /** For example "Live event · Online". */
    public void setEventStatus(Tone tone, String text) {
        eventPill.set(tone, text);
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(Tok.c("header"));
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(Tok.c("border"));
        g.fillRect(0, getHeight() - 1, getWidth(), 1);
    }
}
