package com.mcreatik.uploader.ui.components;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

import javax.swing.JButton;
import javax.swing.Timer;

import com.formdev.flatlaf.FlatClientProperties;
import com.mcreatik.uploader.ui.theme.Icons;

/**
 * One camera setting (Server, Port, User, Password) with a Copy button that confirms "Copied". A secret value is
 * masked until the eye button is pressed, and is never put in a tooltip.
 */
public final class CredentialField extends Ui.Surface {
    private final boolean secret;
    private final Ui.TLabel value = Ui.label("…", Font.BOLD, 15f, "text");
    private final JButton copy = iconButton("copy", "Copy");
    private final JButton reveal;
    private String real = "";
    private boolean shown;

    public CredentialField(String name, boolean secret) {
        super(new BorderLayout(0, 2), "inset", "border", 10, new Insets(8, 12, 8, 6));
        this.secret = secret;
        Ui.TLabel caption = Ui.caps(name);
        javax.swing.JPanel top = Ui.clear(new BorderLayout());
        top.add(caption, BorderLayout.WEST);
        javax.swing.JPanel tools = Ui.row(0);
        if (secret) {
            reveal = iconButton("eye", "Show password");
            reveal.addActionListener(e -> {
                shown = !shown;
                reveal.setIcon(Icons.get(shown ? "eye-off" : "eye", 15, "muted"));
                reveal.setToolTipText(shown ? "Hide password" : "Show password");
                render();
            });
            tools.add(reveal);
        } else {
            reveal = null;
        }
        tools.add(copy);
        top.add(tools, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);
        add(value, BorderLayout.CENTER);
        copy.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(real), null);
            copy.setIcon(Icons.get("check", 15, "green"));
            copy.setToolTipText("Copied");
            caption.text("COPIED");
            caption.tint("green");
            Timer reset = new Timer(1500, ev -> {
                copy.setIcon(Icons.get("copy", 15, "muted"));
                copy.setToolTipText("Copy");
                caption.text(name.toUpperCase());
                caption.tint("muted");
            });
            reset.setRepeats(false);
            reset.start();
        });
        getAccessibleContext().setAccessibleName(name);
    }

    public void set(String v) {
        String nv = v == null ? "" : v;
        if (!nv.equals(real)) {
            real = nv;
            render();
        }
    }

    private void render() {
        value.text(secret && !shown ? "•".repeat(Math.max(8, real.length())) : real);
    }

    private static JButton iconButton(String icon, String tip) {
        JButton b = new JButton(Icons.get(icon, 15, "muted"));
        b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        b.setToolTipText(tip);
        b.setFocusable(true);
        b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        b.getAccessibleContext().setAccessibleName(tip);
        return b;
    }
}
