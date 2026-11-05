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

import com.mcreatik.uploader.CameraRuntime;
import com.mcreatik.uploader.Cameras;
import com.mcreatik.uploader.UploaderMain;
import com.mcreatik.uploader.config.EventSwitch;
import com.mcreatik.uploader.engine.EngineStatus;
import com.mcreatik.uploader.queue.QueueItem;
import com.mcreatik.uploader.source.FtpPhotoSource;
import com.mcreatik.uploader.ui.components.CameraStrip;
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
    private CameraStrip strip;
    private boolean adding;

    private UploaderWindow(Cameras cameras, boolean originals) {
        this.ctx = new AppContext(cameras, originals, this);
    }

    /** Opens the window for this laptop's cameras (Camera 1 selected). */
    public static void open(Cameras cameras, Path dataDir, boolean uploadOriginals) {
        SwingUtilities.invokeLater(() -> {
            UiTheme.apply(); // the saved theme, before any component exists (no flash of the wrong one)
            new UploaderWindow(cameras, uploadOriginals).build();
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
        strip = new CameraStrip(this::selectCamera, this::addCamera, this::removeCamera, this::togglePause,
                this::renameCamera);
        JPanel stripBar = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                g.setColor(com.mcreatik.uploader.ui.theme.Tok.c("bg"));
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        stripBar.setBorder(javax.swing.BorderFactory.createEmptyBorder(14, 24, 0, 24));
        stripBar.add(strip, BorderLayout.CENTER);
        JPanel main = new JPanel(new BorderLayout());
        main.add(stripBar, BorderLayout.NORTH);
        main.add(cards, BorderLayout.CENTER);
        JPanel body = new JPanel(new BorderLayout());
        body.add(sidebar, BorderLayout.WEST);
        body.add(main, BorderLayout.CENTER);
        ctx.cameras().onChange(() -> SwingUtilities.invokeLater(this::refresh));
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
            Instant now = Instant.now();
            CameraRuntime selected = ctx.selected();
            Snapshot snap = null;
            List<CameraStrip.Entry> entries = new java.util.ArrayList<>();
            LiveState worst = null;
            EngineStatus anyStatus = null;
            for (CameraRuntime c : ctx.cameras().all()) {
                EngineStatus s = c.engine().status();
                FtpPhotoSource.Status f = c.ftp() == null ? null : c.ftp().status();
                List<QueueItem> recent = c.queue().recent(RECENT_READ);
                LiveState live = LiveState.of(s, f, LiveUploadPage.lastPhotoAt(f, recent), now);
                entries.add(new CameraStrip.Entry(c, s.uploaderName() == null ? "Camera " + c.number() : s.uploaderName(),
                        live, f == null ? 0 : f.port()));
                if (worst == null || severity(live) > severity(worst)) {
                    worst = live;
                }
                if (anyStatus == null) {
                    anyStatus = s;
                }
                if (c == selected) {
                    snap = new Snapshot(s, f, live, recent, now);
                }
            }
            if (snap == null) {
                return;
            }
            galleryUrl = snap.engine().galleryUrl();
            strip.set(entries, selected, ctx.cameras().canAdd() && !adding, addHint());
            LiveState.Conn worstConn = worst.conn();
            int inWorst = (int) entries.stream().filter(en -> en.state().conn() == worstConn).count();
            header.setEventStatus(worst.tone(), headerText(anyStatus, worst, entries.size(), inWorst));
            sideStatus.text((entries.size() > 1 ? entries.size() + " cameras · " : (snap.engine().uploaderName() == null ? ""
                    : snap.engine().uploaderName() + " · ")) + word(worst.conn()));
            Page p = pages.get(visible);
            p.refresh(snap);
            if (!visible.equals(LiveUploadPage.ID)) {
                pages.get(LiveUploadPage.ID).refresh(snap); // keep the main page current for when it's shown again
            }
        } catch (RuntimeException e) {
            log.warning("Window refresh failed: " + e); // never let a display problem stop the timer
        }
    }

    /** Worst first: a camera that needs attention decides the header colour. */
    static int severity(LiveState s) {
        return switch (s.conn()) {
            case ONLINE -> 0;
            case PAUSED -> 1;
            case RECONNECTING -> 2;
            case OFFLINE -> 3;
            case ERROR -> 4;
        };
    }

    static String word(LiveState.Conn c) {
        return switch (c) {
            case ONLINE -> "Online";
            case PAUSED -> "Paused";
            case RECONNECTING -> "Reconnecting";
            case OFFLINE -> "Offline";
            case ERROR -> "Needs attention";
        };
    }

    private String addHint() {
        if (ctx.cameras().first().ftp() == null) {
            return "Adding cameras needs camera Wi-Fi (FTP)";
        }
        return ctx.cameras().all().size() >= com.mcreatik.uploader.config.CameraSet.MAX
                ? "Up to " + com.mcreatik.uploader.config.CameraSet.MAX + " cameras per computer"
                : "Receive photos from another camera on this computer";
    }

    /** For example "Live event · 4 cameras online" or "Live event · 1 camera needs attention". */
    static String headerText(EngineStatus s, LiveState live, int cameras, int inThatState) {
        String conn;
        if (cameras <= 1) {
            conn = word(live.conn());
        } else {
            String n = inThatState + (inThatState == 1 ? " camera " : " cameras ");
            conn = n + switch (live.conn()) {
                case ONLINE -> "online";
                case PAUSED -> "paused";
                case RECONNECTING -> "reconnecting";
                case OFFLINE -> "offline";
                case ERROR -> inThatState == 1 ? "needs attention" : "need attention";
            };
        }
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
        String blocked = ctx.cameras().blockedReason(); // every camera's photos must have arrived
        if (blocked != null) {
            JOptionPane.showMessageDialog(frame, "<html><body style='width:300px'>" + blocked + "</body></html>",
                    "Change event", JOptionPane.WARNING_MESSAGE);
            return;
        }
        UploaderMain.changeEvent(ctx.cameras().first().config());
    }

    @Override
    public void selectCamera(CameraRuntime camera) {
        ctx.select(camera);
        refresh();
    }

    /** One click: the server adds the next camera to this event; it starts receiving at once. */
    @Override
    public void addCamera() {
        if (adding || !ctx.cameras().canAdd()) {
            return;
        }
        int next = com.mcreatik.uploader.config.CameraSet.nextNumber(
                ctx.cameras().all().stream().map(CameraRuntime::number).toList());
        String typed = (String) JOptionPane.showInputDialog(frame,
                "<html><body style='width:300px'>Name this camera, for example <b>Candid - Divya</b> or <b>Drone</b>."
                        + " Its photos go into a folder with this name.</body></html>",
                "Add camera", JOptionPane.PLAIN_MESSAGE, null, null, "Camera " + next);
        if (typed == null) {
            return; // cancelled
        }
        String cameraName = typed.trim().length() > 60 ? typed.trim().substring(0, 60) : typed.trim();
        adding = true;
        refresh();
        new javax.swing.SwingWorker<CameraRuntime, Void>() {
            @Override
            protected CameraRuntime doInBackground() throws Exception {
                return ctx.cameras().add(cameraName);
            }

            @Override
            protected void done() {
                adding = false;
                try {
                    CameraRuntime c = get();
                    ctx.select(c);
                    refresh();
                    String n = c.engine().status().uploaderName();
                    JOptionPane.showMessageDialog(frame, "<html><body style='width:320px'><b>" + (n == null || n.isBlank()
                            ? "Camera " + c.number() : n) + " is ready.</b><br><br>In that camera's FTP settings use <b>Port "
                            + c.config().ftp().port()
                            + "</b>. The Server, User and Password are the same as Camera 1.</body></html>",
                            "Camera added", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    refresh();
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    String msg = cause instanceof com.mcreatik.uploader.api.ApiException api && api.status() == 404
                            ? "The McreatiK server needs an update before cameras can be added here. Ask McreatiK, or add"
                                    + " the camera in the dashboard and run a second uploader."
                            : cause instanceof com.mcreatik.uploader.api.ApiException api
                                    && api.kind() == com.mcreatik.uploader.api.ApiException.Kind.TRANSIENT
                            ? "McreatiK couldn't be reached. Check the internet and try again."
                            : cause.getMessage();
                    JOptionPane.showMessageDialog(frame, "<html><body style='width:320px'>" + msg + "</body></html>",
                            "Add camera", JOptionPane.WARNING_MESSAGE);
                }
            }
        }.execute();
    }

    @Override
    public void removeCamera(CameraRuntime camera) {
        String blocked = com.mcreatik.uploader.config.EventSwitch.blockedReason(camera.queue().counts());
        if (blocked != null) {
            JOptionPane.showMessageDialog(frame, "<html><body style='width:300px'>" + blocked.replace("change event",
                    "remove the camera") + "</body></html>", "Remove camera", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String name = camera.engine().status().uploaderName();
        int ok = JOptionPane.showConfirmDialog(frame, "<html><body style='width:300px'>Remove <b>"
                + (name == null ? "Camera " + camera.number() : name) + "</b> from this computer and the event? Its photos stay"
                + " in the gallery and in its folder.</body></html>", "Remove camera", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            String note = ctx.cameras().remove(camera);
            ctx.select(ctx.cameras().first());
            refresh();
            if (note != null) {
                JOptionPane.showMessageDialog(frame, note, "Remove camera", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, e.getMessage(), "Remove camera", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Privacy pause: new photos stay on this laptop; resume to upload again. */
    @Override
    public void togglePause(CameraRuntime camera) {
        boolean pause = !camera.engine().isPaused();
        if (pause) {
            int ok = JOptionPane.showConfirmDialog(frame, "<html><body style='width:320px'><b>Pause this camera?</b><br><br>"
                    + "Photos not uploaded yet, and every new photo, stay on this laptop in <b>Not uploaded (paused)</b>."
                    + " None of them go to the gallery, even after you resume.<br><br>Use it for private moments, such as"
                    + " the bride getting ready.</body></html>", "Pause camera", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) {
                return;
            }
        }
        camera.engine().setPaused(pause);
        refresh();
    }

    @Override
    public void renameCamera(CameraRuntime camera) {
        String current = camera.engine().status().uploaderName();
        String typed = (String) JOptionPane.showInputDialog(frame, "New name for this camera (shown here and in the"
                + " dashboard; its folder keeps its name):", "Rename camera", JOptionPane.PLAIN_MESSAGE, null, null,
                current == null ? "Camera " + camera.number() : current);
        if (typed == null || typed.isBlank() || typed.trim().equals(current)) {
            return;
        }
        String name = typed.trim().length() > 60 ? typed.trim().substring(0, 60) : typed.trim();
        new javax.swing.SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return camera.engine().rename(name);
            }

            @Override
            protected void done() {
                try {
                    get();
                    refresh();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    String msg = cause instanceof com.mcreatik.uploader.api.ApiException api
                            && api.kind() == com.mcreatik.uploader.api.ApiException.Kind.TRANSIENT
                            ? "McreatiK couldn't be reached. Check the internet and try again." : cause.getMessage();
                    JOptionPane.showMessageDialog(frame, "<html><body style='width:300px'>" + msg + "</body></html>",
                            "Rename camera", JOptionPane.WARNING_MESSAGE);
                }
            }
        }.execute();
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
