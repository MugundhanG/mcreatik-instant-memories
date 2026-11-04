package com.mcreatik.uploader.ui.components;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Insets;
import java.util.Locale;

import javax.swing.JLabel;

import com.mcreatik.uploader.ui.LiveState.Tone;
import com.mcreatik.uploader.ui.theme.Icons;

/** One queue counter: icon in a tinted square, the number large, the label below. */
public final class MetricCard extends Ui.Surface {
    private final Tone tone;
    private final boolean colourWhenNonZero;
    private final Ui.TLabel value = Ui.label("0", Font.BOLD, 26f, "text");
    private final Icons.Icon icon;
    private final Ui.Surface badge;
    private int last = -1;

    /**
     * @param colourWhenNonZero the number takes the tone's colour only when it isn't 0 (Uploaded, Failed); otherwise
     *                          the number stays neutral so 0 never looks alarming
     */
    public MetricCard(String label, String iconName, Tone tone, boolean colourWhenNonZero) {
        super(new BorderLayout(0, 6), "card", "border", Ui.ARC, new Insets(12, 14, 12, 14));
        this.tone = tone;
        this.colourWhenNonZero = colourWhenNonZero;
        icon = Icons.get(iconName, 16, tone.token);
        badge = new Ui.Surface(new BorderLayout(), tone.token + "Soft", null, 10, new Insets(6, 6, 6, 6));
        badge.add(new JLabel(icon), BorderLayout.CENTER);
        BorderLayout top = new BorderLayout(10, 0);
        javax.swing.JPanel head = Ui.clear(top);
        head.add(badge, BorderLayout.WEST);
        head.add(Ui.label(label, Font.PLAIN, 12.5f, "muted"), BorderLayout.CENTER);
        add(head, BorderLayout.NORTH);
        add(value, BorderLayout.CENTER);
        getAccessibleContext().setAccessibleName(label);
    }

    public void set(int n) {
        if (n == last) {
            return;
        }
        last = n;
        value.text(String.format(Locale.ROOT, "%,d", n));
        value.tint(colourWhenNonZero && n > 0 ? tone.token : "text");
        boolean active = !colourWhenNonZero || n > 0;
        badge.tint(active ? tone.token + "Soft" : "greySoft", null);
        icon.tint(active ? tone.token : "faint");
        repaint();
    }
}
