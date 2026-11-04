package com.mcreatik.uploader.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

import com.mcreatik.uploader.UploaderMain;
import com.mcreatik.uploader.config.EventSwitch;
import com.mcreatik.uploader.config.UploaderConfig;
import com.mcreatik.uploader.engine.EngineStatus;
import com.mcreatik.uploader.engine.UploadEngine;
import com.mcreatik.uploader.queue.QueueItem;
import com.mcreatik.uploader.source.FtpPhotoSource;
import com.mcreatik.uploader.ui.components.HeaderBar;
import com.mcreatik.uploader.ui.components.Sidebar;
import com.mcreatik.uploader.ui.components.Ui;
import com.mcreatik.uploader.ui.pages.AboutPage;
import com.mcreatik.uploader.ui.pages.EventsPage;
import com.mcreatik.uploader.ui.pages.GalleryPage;
import com.mcreatik.uploader.ui.pages.LiveUploadPage;
import com.mcreatik.uploader.ui.pages.SettingsPage;
import com.mcreatik.uploader.ui.theme.Theme;

/**
 * The main window: header (logo, live event status, Light/Dark switch), a compact sidebar, and five pages. One timer
 * reads the engine and the camera FTP server every half second and hands the same snapshot to the visible page.
 * Closing the window stops uploading; queued photos resume next time the app starts.
 */
public final class UploaderWindow implements AppContext.Actions {

    private static final Logger log = Logger.getLogger(UploaderWindow.class.getName());
    /** Recent rows read each tick: the Live page shows a few; "Last photo" uses the newest. */
    private static final int RECENT_READ = 20;

    private final AppContext ctx;
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final JPanel cards = new JPanel(new CardLayout());
    private JFrame frame;
    private Sidebar sidebar;
    private HeaderBar header;
    private Ui.TLabel sideStatus;
    private String visible = LiveUploadPage.ID;
    private String galleryUrl;

    private UploaderWindow(UploadEngine engine, UploaderConfig config, FtpPhotoSource ftp, Path dataDir, boolean originals) {
        this.ctx = new AppContext(engine, config, ftp, dataDir, originals, this);
    }

    /** @param ftp the built-in camera FTP server, or null when the camera writes into the folder itself */
    public static void open(UploadEngine engine, UploaderConfig config, FtpPhotoSource ftp, Path dataDir, boolean uploadOriginals) {
        SwingUtilities.invokeLater(() -> {
            UiTheme.apply(); // the saved theme, before any component exists (no flash of the wrong one)
            new UploaderWindow(engine, config, ftp, dataDir, uploadOriginals).build();
        });
    }

    private void build() {
        frame = new JFrame(UiTheme.APP_NAME);
        frame.setIconImages(UiTheme.windowIcons());
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        Theme.applyTitleBar(frame, Theme.current());

        for (Page p : List.of(new LiveUploadPage(ctx), new GalleryPage(ctx), new EventsPage(ctx), new SettingsPage(ctx),
                new AboutPage(ctx))) {
            pages.put(p.id(), p);
            cards.add(p.view(), p.id());
        }
        sidebar = new Sidebar(List.of(
                new Sidebar.Item(LiveUploadPage.ID, "Live Upload", "radio"),
                new Sidebar.Item(GalleryPage.ID, "Gallery", "images"),
                new Sidebar.Item(EventsPage.ID, "Events", "calendar"),
                new Sidebar.Item(SettingsPage.ID, "Settings", "settings"),
                new Sidebar.Item(AboutPage.ID, "About", "info")), this::show);
        sideStatus = Ui.label(" ", Font.PLAIN, 11.5f, "muted");
        JPanel foot = Ui.clear(new java.awt.GridLayout(2, 1, 0, 2));
        foot.add(sideStatus);
        foot.add(Ui.label("Version " + ctx.version(), Font.PLAIN, 11.5f, "faint"));
        sidebar.footer().add(foot, BorderLayout.CENTER);
        sidebar.select(visible);

        header = new HeaderBar(UiTheme.logo(30));
        JPanel body = new JPanel(new BorderLayout());
        body.add(sidebar, BorderLayout.WEST);
        body.add(cards, BorderLayout.CENTER);
        JPanel root = new JPanel(new BorderLayout());
        root.add(header, BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);
        frame.setContentPane(root);

        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        frame.setMinimumSize(new Dimension(Math.min(1100, screen.width), Math.min(720, screen.height)));
        frame.setSize(Math.min(1320, screen.width), Math.min(940, screen.height));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        Theme.onChange(this::refresh); // status colours follow a theme switch at once
        new Timer(500, e -> refresh()).start();
        refresh();
    }

    // ---- live updates ----

    private void refresh() {
        try {
            EngineStatus s = ctx.engine().status();
            FtpPhotoSource.Status f = ctx.ftp() == null ? null : ctx.ftp().status();
            List<QueueItem> recent = ctx.engine().queue().recent(RECENT_READ);
            Instant now = Instant.now();
            LiveState live = LiveState.of(s, f, LiveUploadPage.lastPhotoAt(f, recent), now);
            Snapshot snap = new Snapshot(s, f, live, recent, now);
            galleryUrl = s.galleryUrl();
            header.setEventStatus(live.tone(), headerText(s, live));
            sideStatus.text((s.uploaderName() == null ? "" : s.uploaderName() + " · ") + switch (live.conn()) {
                case ONLINE -> "Online";
                case RECONNECTING -> "Reconnecting";
                case OFFLINE -> "Offline";
                case ERROR -> "Needs attention";
            });
            Page p = pages.get(visible);
            p.refresh(snap);
            if (!visible.equals(LiveUploadPage.ID)) {
                pages.get(LiveUploadPage.ID).refresh(snap); // keep the main page current for when it's shown again
            }
        } catch (RuntimeException e) {
            log.warning("Window refresh failed: " + e); // never let a display problem stop the timer
        }
    }

    static String headerText(EngineStatus s, LiveState live) {
        String conn = switch (live.conn()) {
            case ONLINE -> "Online";
            case RECONNECTING -> "Reconnecting";
            case OFFLINE -> "Offline";
            case ERROR -> "Needs attention";
        };
        if (s.eventStatus() == null) {
            return conn;
        }
        String event = switch (s.eventStatus()) {
            case "LIVE" -> "Live event";
            case "UPCOMING" -> "Upcoming event";
            case "COMPLETED" -> "Completed event";
            default -> "Event not visible to guests";
        };
        return event + " · " + conn;
    }

    // ---- actions (AppContext.Actions) ----

    @Override
    public void show(String pageId) {
        visible = pageId;
        ((CardLayout) cards.getLayout()).show(cards, pageId);
        sidebar.select(pageId);
        refresh();
    }

    /** Only with an empty queue, so no photo from this event can end up in the next one. */
    @Override
    public void changeEvent() {
        String blocked = EventSwitch.blockedReason(ctx.engine().queue().counts());
        if (blocked != null) {
            JOptionPane.showMessageDialog(frame, "<html><body style='width:300px'>" + blocked + "</body></html>",
                    "Change event", JOptionPane.WARNING_MESSAGE);
            return;
        }
        UploaderMain.changeEvent(ctx.config());
    }

    @Override
    public void openFolder(Path folder) {
        browse(folder.toUri().toString());
    }

    @Override
    public void openGallery() {
        if (galleryUrl != null) {
            browse(galleryUrl);
        }
    }

    @Override
    public void browse(String url) {
        try {
            URI uri = URI.create(url);
            if (url.startsWith("mailto:") && Desktop.getDesktop().isSupported(Desktop.Action.MAIL)) {
                Desktop.getDesktop().mail(uri);
            } else {
                Desktop.getDesktop().browse(uri);
            }
        } catch (Exception e) {
            log.warning("Could not open " + url + ": " + e.getMessage());
            JOptionPane.showMessageDialog(frame, "This computer couldn't open it automatically:\n" + url,
                    UiTheme.APP_NAME, JOptionPane.INFORMATION_MESSAGE);
        }
    }

}
