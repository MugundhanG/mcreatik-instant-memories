package com.mcreatik.uploader.ui.pages;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;

import com.formdev.flatlaf.FlatClientProperties;
import com.mcreatik.uploader.engine.EngineStatus;
import com.mcreatik.uploader.queue.QueueCounts;
import com.mcreatik.uploader.queue.QueueItem;
import com.mcreatik.uploader.source.FtpPhotoSource;
import com.mcreatik.uploader.ui.AppContext;
import com.mcreatik.uploader.ui.LiveState.Tone;
import com.mcreatik.uploader.ui.Page;
import com.mcreatik.uploader.ui.Snapshot;
import com.mcreatik.uploader.ui.components.CredentialField;
import com.mcreatik.uploader.ui.components.EmptyState;
import com.mcreatik.uploader.ui.components.MetricCard;
import com.mcreatik.uploader.ui.components.PageLayout;
import com.mcreatik.uploader.ui.components.PhotoList;
import com.mcreatik.uploader.ui.components.PipelineView;
import com.mcreatik.uploader.ui.components.RatioRow;
import com.mcreatik.uploader.ui.components.ResponsiveGrid;
import com.mcreatik.uploader.ui.components.StatusCard;
import com.mcreatik.uploader.ui.components.Ui;
import com.mcreatik.uploader.ui.theme.Icons;

/**
 * The event-day screen: which event, is it working, what to type into the camera, the counts, where photos are,
 * and the latest photos. Everything is read from the engine and the camera FTP server every half second.
 */
public final class LiveUploadPage implements Page {
    public static final String ID = "live";
    static final int RECENT = 6;

    private final AppContext ctx;
    private final JPanel root = new JPanel(new BorderLayout());

    private final EventCard event;
    private final StatusCard status = new StatusCard();
    private final CredentialField[] ftpFields = {new CredentialField("Server", false), new CredentialField("Port", false),
            new CredentialField("User", false), new CredentialField("Password", true)};
    private final Ui.TLabel ftpNote = Ui.label(" ", Font.PLAIN, 12f, "muted");
    private final MetricCard[] metrics = {
            new MetricCard("Waiting", "hourglass", Tone.GREY, false),
            new MetricCard("Uploading", "upload", Tone.BLUE, false),
            new MetricCard("Uploaded", "circle-check", Tone.GREEN, true),
            new MetricCard("Already sent", "copy-check", Tone.GREY, false),
            new MetricCard("Failed", "circle-x", Tone.RED, true)};
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final Ui.TLabel progressText = Ui.label(" ", Font.BOLD, 13f, "text");
    private final Ui.TLabel progressPct = Ui.label(" ", Font.BOLD, 13f, "green");
    private final PipelineView pipeline = new PipelineView();
    private final PhotoList recent = new PhotoList();
    private final JPanel recentBody = Ui.clear(new CardLayout());
    private final EmptyState empty;
    private final Ui.Btn retry = new Ui.Btn("Retry failed", Ui.Kind.SECONDARY, "rotate-cw");
    private final Ui.Btn gallery = new Ui.Btn("Open live gallery", Ui.Kind.PRIMARY, "external-link");

    public LiveUploadPage(AppContext ctx) {
        this.ctx = ctx;
        event = new EventCard(ctx);
        PageLayout page = new PageLayout(null, null);

        page.section(event);

        JPanel row = Ui.clear(new RatioRow(14, 0.58, 0.42));
        row.add(status);
        row.add(ctx.ftp() != null ? ftpCard() : folderCard());
        page.section(row);

        JPanel grid = Ui.clear(new ResponsiveGrid(150, 12));
        for (MetricCard m : metrics) {
            grid.add(m);
        }
        page.section(grid);
        page.section(progressCard());
        page.section(pipeline);

        empty = new EmptyState("images", "No photos yet",
                ctx.ftp() != null ? "Take a test shot. It appears here within seconds."
                        : "Photos saved into the folder appear here within seconds.");
        page.fill(recentCard());

        root.add(page, BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public JComponent view() {
        return root;
    }

    private JComponent ftpCard() {
        Ui.Surface card = Ui.Surface.card(new BorderLayout(0, 10));
        JPanel head = Ui.clear(new GridLayout(2, 1, 0, 2));
        JLabel title = Ui.label("Camera Wi-Fi settings", Font.BOLD, 14f, "text");
        title.setIcon(Icons.get("wifi", 16, "text"));
        title.setIconTextGap(8);
        head.add(title);
        head.add(Ui.label("Type these into the camera's FTP settings", Font.PLAIN, 12f, "muted"));
        card.add(head, BorderLayout.NORTH);
        JPanel grid = Ui.clear(new GridLayout(2, 2, 8, 8));
        for (CredentialField f : ftpFields) {
            grid.add(f);
        }
        card.add(grid, BorderLayout.CENTER);
        card.add(ftpNote, BorderLayout.SOUTH);
        return card;
    }

    private JComponent folderCard() {
        Ui.Surface card = Ui.Surface.card(new BorderLayout(0, 10));
        JLabel title = Ui.label("Photo folder", Font.BOLD, 14f, "text");
        title.setIcon(Icons.get("folder-open", 16, "text"));
        title.setIconTextGap(8);
        card.add(title, BorderLayout.NORTH);
        Ui.TLabel path = Ui.label(ctx.config().watchFolder().toString(), Font.BOLD, 13f, "text");
        path.setToolTipText(ctx.config().watchFolder().toString());
        JPanel body = Ui.clear(new GridLayout(2, 1, 0, 4));
        body.add(path);
        body.add(Ui.label("Your camera software saves photos here; each new one is uploaded.", Font.PLAIN, 12f, "muted"));
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    private JComponent progressCard() {
        Ui.Surface card = Ui.Surface.card(new BorderLayout(0, 8));
        card.setBorder(BorderFactory.createEmptyBorder(12, 18, 14, 18));
        JPanel top = Ui.clear(new BorderLayout());
        top.add(progressText, BorderLayout.WEST);
        top.add(progressPct, BorderLayout.EAST);
        card.add(top, BorderLayout.NORTH);
        progress.putClientProperty(FlatClientProperties.STYLE, "arc: 999");
        progress.setPreferredSize(new java.awt.Dimension(10, 8));
        progress.getAccessibleContext().setAccessibleName("Upload progress");
        card.add(progress, BorderLayout.CENTER);
        return card;
    }

    private JComponent recentCard() {
        Ui.Surface card = Ui.Surface.card(new BorderLayout(0, 6));
        JPanel head = Ui.clear(new BorderLayout());
        head.add(Ui.label("Recent photos", Font.BOLD, 14f, "text"), BorderLayout.WEST);
        Ui.Btn all = new Ui.Btn("View all", Ui.Kind.GHOST, null);
        all.addActionListener(e -> ctx.actions().show(GalleryPage.ID));
        head.add(all, BorderLayout.EAST);
        card.add(head, BorderLayout.NORTH);
        recentBody.add(recent, "list");
        recentBody.add(empty, "empty");
        recentBody.setMinimumSize(new java.awt.Dimension(10, 200));
        recentBody.setPreferredSize(new java.awt.Dimension(10, 6 * 62));
        card.add(recentBody, BorderLayout.CENTER);
        return card;
    }

    private JComponent footer() {
        JPanel bar = new JPanel(new BorderLayout(12, 0)) {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                g.setColor(com.mcreatik.uploader.ui.theme.Tok.c("header"));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(com.mcreatik.uploader.ui.theme.Tok.c("border"));
                g.fillRect(0, 0, getWidth(), 1);
            }
        };
        bar.setBorder(BorderFactory.createEmptyBorder(12, 24, 12, 24));
        Path folder = ctx.config().watchFolder();
        Ui.TLabel where = Ui.label((ctx.ftp() != null ? "Photos saved in  " : "Watching  ") + folder, Font.PLAIN, 12f, "muted");
        where.setIcon(Icons.get("hard-drive", 15, "muted"));
        where.setIconTextGap(8);
        where.setToolTipText(folder.toString());
        bar.add(where, BorderLayout.CENTER);
        Ui.Btn open = new Ui.Btn("Open folder", Ui.Kind.SECONDARY, "folder-open");
        open.addActionListener(e -> ctx.actions().openFolder(folder));
        retry.addActionListener(e -> ctx.engine().retryFailed());
        gallery.addActionListener(e -> ctx.actions().openGallery());
        JPanel buttons = Ui.clear(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(open);
        buttons.add(retry);
        buttons.add(gallery);
        bar.add(buttons, BorderLayout.EAST);
        return bar;
    }

    @Override
    public void refresh(Snapshot s) {
        EngineStatus e = s.engine();
        event.refresh(s);
        status.set(s.live());
        if (s.ftp() != null) {
            FtpPhotoSource.Status f = s.ftp();
            ftpFields[0].set(f.addresses().isEmpty() ? "No Wi-Fi" : String.join(" / ", f.addresses()));
            ftpFields[1].set(Integer.toString(f.port()));
            ftpFields[2].set(f.username());
            ftpFields[3].set(f.password());
            ftpNote.text(f.addresses().isEmpty() ? "Join the camera's Wi-Fi or hotspot to get a server address."
                    : "Plain FTP (not FTPS) · passive mode on or off");
            ftpNote.tint(f.addresses().isEmpty() ? "amber" : "muted");
        }
        QueueCounts c = e.counts();
        int[] v = {c.queued(), c.uploading(), c.done(), c.duplicate(), c.failed()};
        for (int i = 0; i < v.length; i++) {
            metrics[i].set(v[i]);
        }
        int total = s.live().total();
        int up = s.live().uploaded();
        progress.setValue(total == 0 ? 0 : (int) Math.round(1000.0 * up / total));
        progressText.text(total == 0 ? "No photos detected yet"
                : String.format(Locale.ROOT, "Uploaded %,d of %,d photo%s detected", up, total, total == 1 ? "" : "s"));
        progressPct.text(total == 0 ? " " : (int) Math.floor(100.0 * up / total) + "%");
        pipeline.set(s.live().pipeline());

        List<QueueItem> latest = s.recent().size() > RECENT ? s.recent().subList(0, RECENT) : s.recent();
        recent.set(latest);
        ((CardLayout) recentBody.getLayout()).show(recentBody, latest.isEmpty() ? "empty" : "list");
        retry.setEnabled(c.failed() > 0);
        retry.setToolTipText(c.failed() > 0 ? "Try the failed photos again" : "No failed photos");
        gallery.setEnabled(e.galleryUrl() != null);
    }

    /** Newest photo time of this event, for "Last photo". */
    public static Instant lastPhotoAt(FtpPhotoSource.Status ftp, List<QueueItem> recent) {
        Instant newest = ftp == null ? null : ftp.lastFileAt();
        for (QueueItem i : recent) {
            Instant t = Instant.ofEpochMilli(i.createdAt());
            if (newest == null || t.isAfter(newest)) {
                newest = t;
            }
        }
        return newest;
    }
}
