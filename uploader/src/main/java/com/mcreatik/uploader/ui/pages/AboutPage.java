package com.mcreatik.uploader.ui.pages;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.mcreatik.uploader.ui.AppContext;
import com.mcreatik.uploader.ui.Page;
import com.mcreatik.uploader.ui.Snapshot;
import com.mcreatik.uploader.ui.UiTheme;
import com.mcreatik.uploader.ui.components.PageLayout;
import com.mcreatik.uploader.ui.components.Ui;

/** Version, support and licences. */
public final class AboutPage implements Page {
    public static final String ID = "about";

    private final PageLayout page = new PageLayout("About", "McreatiK Instant Memories: live wedding and event galleries.");

    public AboutPage(AppContext ctx) {
        Ui.Surface app = Ui.Surface.card(new BorderLayout(16, 0));
        app.add(new JLabel(UiTheme.logo(56)), BorderLayout.WEST);
        JPanel t = Ui.clear(new GridLayout(3, 1, 0, 2));
        t.add(Ui.label(UiTheme.APP_NAME, Font.BOLD, 16f, "text"));
        t.add(Ui.label("Version " + ctx.version(), Font.PLAIN, 13f, "muted"));
        t.add(Ui.label("Field-tested with Canon EOS R6 Mark II on Windows.", Font.PLAIN, 13f, "muted"));
        app.add(t, BorderLayout.CENTER);
        page.section(app);

        Ui.Surface help = Ui.Surface.card(new BorderLayout(0, 10));
        JPanel head = Ui.clear(new GridLayout(2, 1, 0, 2));
        head.add(Ui.label("Help on event day", Font.BOLD, 14f, "text"));
        head.add(Ui.label("Camera settings, laptop or hotspot: the studio's guides. Login, gallery or anything else: McreatiK.",
                Font.PLAIN, 12.5f, "muted"));
        help.add(head, BorderLayout.NORTH);
        JPanel buttons = Ui.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
        Ui.Btn wa = new Ui.Btn("WhatsApp +91 99527 58545", Ui.Kind.SECONDARY, "message-circle");
        wa.addActionListener(e -> ctx.actions().browse("https://wa.me/919952758545"));
        Ui.Btn mail = new Ui.Btn("connect@mcreatik.com", Ui.Kind.SECONDARY, "mail");
        mail.addActionListener(e -> ctx.actions().browse("mailto:connect@mcreatik.com"));
        buttons.add(wa);
        buttons.add(javax.swing.Box.createHorizontalStrut(8));
        buttons.add(mail);
        help.add(buttons, BorderLayout.CENTER);
        page.section(help);

        Ui.Surface lic = Ui.Surface.card(new BorderLayout(0, 8));
        lic.add(Ui.label("Open-source parts", Font.BOLD, 14f, "text"), BorderLayout.NORTH);
        lic.add(Ui.label("<html>FlatLaf (Apache 2.0) · Inter font (SIL OFL 1.1) · Lucide icons (ISC) · Apache FtpServer (Apache 2.0)"
                + " · SQLite JDBC (Apache 2.0) · metadata-extractor (Apache 2.0) · TwelveMonkeys ImageIO (BSD)</html>",
                Font.PLAIN, 12.5f, "muted"), BorderLayout.CENTER);
        page.section(lic);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public JComponent view() {
        return page;
    }

    @Override
    public void refresh(Snapshot s) {
        // nothing live here
    }
}
