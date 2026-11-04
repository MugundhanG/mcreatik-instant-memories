package com.mcreatik.uploader.ui.pages;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.nio.file.Path;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.mcreatik.uploader.ui.AppContext;
import com.mcreatik.uploader.ui.Page;
import com.mcreatik.uploader.ui.Snapshot;
import com.mcreatik.uploader.ui.components.CredentialField;
import com.mcreatik.uploader.ui.components.PageLayout;
import com.mcreatik.uploader.ui.components.ThemeSwitch;
import com.mcreatik.uploader.ui.components.Ui;
import com.mcreatik.uploader.ui.theme.Icons;

/** Appearance, where photos and settings live, and how photos arrive and are uploaded (read-only, explained). */
public final class SettingsPage implements Page {
    public static final String ID = "settings";

    private final PageLayout page = new PageLayout("Settings", "How this computer receives and uploads photos.");
    private final CredentialField port = new CredentialField("Camera port", false);
    private final CredentialField user = new CredentialField("Camera user", false);

    public SettingsPage(AppContext ctx) {
        Ui.Surface look = section("Appearance", "palette", "Light suits bright venues; Dark is easier on the eyes at night. Saved on this computer.");
        JPanel switchRow = Ui.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
        switchRow.add(new ThemeSwitch());
        look.add(switchRow, BorderLayout.CENTER);
        page.section(look);

        Ui.Surface photos = section("Photos on this computer", "folder-open",
                "Every photo is kept here as well as uploaded. To use another folder, use Change event….");
        photos.add(pathRow(ctx, ctx.config().watchFolder()), BorderLayout.CENTER);
        page.section(photos);

        if (ctx.ftp() != null) {
            Ui.Surface cam = section("Camera Wi-Fi (FTP)", "wifi",
                    "The camera logs in with these. To use another port, start the uploader with --ftp-port 2122.");
            JPanel grid = Ui.clear(new GridLayout(1, 2, 8, 0));
            grid.add(port);
            grid.add(user);
            cam.add(grid, BorderLayout.CENTER);
            page.section(cam);
        }

        Ui.Surface mode = section("Uploading", "cloud-upload", ctx.uploadOriginals()
                ? "Full-size originals are sent and McreatiK prepares them (slower on weak internet)."
                : "A sharp 2048 px copy and a thumbnail are made here and sent (about 1 MB per photo). The original stays on this computer.");
        mode.add(Ui.label(ctx.uploadOriginals() ? "Mode: originals" : "Mode: light copies (recommended)", Font.BOLD, 13f, "text"),
                BorderLayout.CENTER);
        page.section(mode);

        Ui.Surface data = section("Settings and queue", "hard-drive",
                "The connection, camera login and upload queue. Don't delete this folder during an event.");
        data.add(pathRow(ctx, ctx.dataDir()), BorderLayout.CENTER);
        page.section(data);
    }

    private static Ui.Surface section(String title, String icon, String hint) {
        Ui.Surface card = Ui.Surface.card(new BorderLayout(0, 10));
        JPanel head = Ui.clear(new GridLayout(2, 1, 0, 2));
        JLabel t = Ui.label(title, Font.BOLD, 14f, "text");
        t.setIcon(Icons.get(icon, 16, "text"));
        t.setIconTextGap(8);
        head.add(t);
        head.add(Ui.label(hint, Font.PLAIN, 12.5f, "muted"));
        card.add(head, BorderLayout.NORTH);
        return card;
    }

    private static JPanel pathRow(AppContext ctx, Path folder) {
        JPanel row = Ui.clear(new BorderLayout(12, 0));
        Ui.TLabel p = Ui.label(folder.toString(), Font.BOLD, 13f, "text");
        p.setToolTipText(folder.toString());
        row.add(p, BorderLayout.CENTER);
        Ui.Btn open = new Ui.Btn("Open", Ui.Kind.SECONDARY, "folder-open");
        open.addActionListener(e -> ctx.actions().openFolder(folder));
        row.add(open, BorderLayout.EAST);
        return row;
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
        if (s.ftp() != null) {
            port.set(Integer.toString(s.ftp().port()));
            user.set(s.ftp().username());
        }
    }
}
