package com.mcreatik.uploader.ui;

import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import com.mcreatik.uploader.Cameras;
import com.mcreatik.uploader.FakeMcreatikServer;
import com.mcreatik.uploader.TestPhotos;
import com.mcreatik.uploader.api.ApiModels.StudioEvent;
import com.mcreatik.uploader.config.UploaderConfig;
import com.mcreatik.uploader.ui.theme.Theme;

/**
 * Renders the sign-in screens with demo data into PNGs (no network, no real accounts). Run under Xvfb:
 * {@code xvfb-run -a -s "-screen 0 1600x1200x24" java -cp <test classpath> com.mcreatik.uploader.ui.ScreenshotTool <outDir>}.
 */
public final class ScreenshotTool {

    private static final double SCALE = 2.0;
    private static Path out;

    private ScreenshotTool() {
    }

    public static void main(String[] args) throws Exception {
        out = Path.of(args.length > 0 ? args[0] : "screenshots");
        Files.createDirectories(out);
        System.setProperty("mcreatik.theme", "light");
        Theme.applyAtStartup();

        for (Theme theme : new Theme[] {Theme.LIGHT, Theme.DARK}) {
            boolean dark = theme == Theme.DARK;
            SwingUtilities.invokeAndWait(() -> Theme.switchTo(theme));
            String suffix = dark ? "dark" : "light";
            String prefix = dark ? "07-signin-" : "01-signin-";
            if (!dark) {
                shot("01-signin-light", () -> SetupDialog.backdrop(signIn(false, false)));
                shot("02-validation-light", () -> SetupDialog.backdrop(signIn(true, false)));
                shot("03-wrong-password-light", () -> SetupDialog.backdrop(signIn(false, true)));
                shot("04-events-light", () -> SetupDialog.backdrop(events(true, true)));
                shot("05-events-empty-light", () -> SetupDialog.backdrop(events(false, false)));
                livePage("06-live-light");
            } else {
                shot("07-signin-dark", () -> SetupDialog.backdrop(signIn(false, false)));
                shot("08-events-dark", () -> SetupDialog.backdrop(events(true, true)));
            }
        }
        System.exit(0);
    }

    // ---- screens ----

    private static JComponent signIn(boolean errors, boolean wrongPassword) {
        SignInPanel panel = new SignInPanel(new SignInPanel.Handler() {
            @Override
            public void signIn(String email, char[] password) {
            }

            @Override
            public void forgotPassword() {
            }

            @Override
            public void useConnectionCode() {
            }
        }, null);
        if (errors) {
            panel.fill("hello@lenslight", "");
            panel.showAllErrors();
        }
        if (wrongPassword) {
            panel.fill("hello@lenslight.test", "not-my-password");
            panel.showBanner("Email or password is wrong");
        }
        return panel;
    }

    private static JComponent events(boolean withEvents, boolean select) {
        EventPickerPanel[] picker = new EventPickerPanel[1];
        com.mcreatik.uploader.ui.components.Ui.Surface card = new com.mcreatik.uploader.ui.components.Ui.Surface(
                new java.awt.BorderLayout(0, 14), "card", "border", 18, new java.awt.Insets(24, 28, 20, 28));
        card.shadow(true);
        javax.swing.JButton cancel = new com.mcreatik.uploader.ui.components.Ui.Btn("Cancel",
                com.mcreatik.uploader.ui.components.Ui.Kind.SECONDARY, null);
        javax.swing.JButton next = new com.mcreatik.uploader.ui.components.Ui.Btn("Continue",
                com.mcreatik.uploader.ui.components.Ui.Kind.PRIMARY, null);
        next.setEnabled(false);
        picker[0] = new EventPickerPanel(() -> {
        }, ev -> next.setEnabled(ev != null));
        picker[0].setPreferredSize(new Dimension(520, 440));
        picker[0].setSignedIn("hello@lenslight.test");
        List<StudioEvent> list = withEvents ? List.of(
                new StudioEvent("1", "Arun & Priya Wedding", "2026-12-12", "UPCOMING", "2026-02-01T00:00:00Z"),
                new StudioEvent("2", "Reception Night", "2026-11-11", "LIVE", "2026-04-01T00:00:00Z"),
                new StudioEvent("3", "Kavya's 30th Birthday", "2026-03-02", "COMPLETED", "2026-01-10T00:00:00Z"),
                new StudioEvent("4", "Housewarming at Lens & Light Studio", null, "DRAFT", "2026-05-01T00:00:00Z"),
                new StudioEvent("5", "Old Closed Event", "2027-01-01", "ARCHIVED", "2026-02-05T00:00:00Z"))
                : List.of();
        picker[0].setEvents(list);
        if (select) {
            picker[0].select(EventListHelper.byName(list, "Arun & Priya Wedding"));
        }
        card.add(picker[0], java.awt.BorderLayout.CENTER);
        javax.swing.JPanel buttons = new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancel);
        buttons.add(next);
        card.add(buttons, java.awt.BorderLayout.SOUTH);
        return card;
    }

    private static final class EventListHelper {
        static StudioEvent byName(List<StudioEvent> list, String name) {
            return list.stream().filter(e -> e.name().equals(name)).findFirst().orElse(null);
        }
    }

    /** The real window, connected to a fake McreatiK server, with a few demo photos already uploaded. */
    private static void livePage(String name) throws Exception {
        Path root = Files.createTempDirectory("shot");
        Path photos = root.resolve("McreatiK Photos").resolve("Arun & Priya Wedding").resolve("Camera 1");
        for (int i = 1; i <= 6; i++) {
            TestPhotos.write(photos, "IMG_" + (1200 + i) + ".JPG", TestPhotos.jpeg(1200, 800));
        }
        try (FakeMcreatikServer server = new FakeMcreatikServer()) {
            server.eventName = "Arun & Priya Wedding";
            server.cameraName = "Camera 1";
            UploaderConfig config = new UploaderConfig(server.url(), FakeMcreatikServer.TOKEN, photos, 2, "lens-laptop-1a2b",
                    new UploaderConfig.Ftp(false, 2121, "mcreatik", "pw234567"));
            Cameras cameras = Cameras.start(root.resolve("data"), config, false);
            try {
                UploaderWindow.open(cameras, root.resolve("data"), false);
                Thread.sleep(7000);
                for (Frame f : Frame.getFrames()) {
                    if (f.isShowing() && f instanceof JFrame jf) {
                        SwingUtilities.invokeAndWait(() -> {
                        });
                        write(name, jf.getContentPane());
                        jf.dispose();
                    }
                }
            } finally {
                cameras.closeAll();
            }
        }
    }

    // ---- rendering ----

    private interface Content {
        JComponent create();
    }

    private static void shot(String name, Content content) throws Exception {
        JFrame[] frame = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            frame[0] = new JFrame(name);
            frame[0].setContentPane((javax.swing.JPanel) content.create());
            frame[0].setSize(640, 740);
            frame[0].setLocation(20, 20);
            frame[0].setVisible(true);
        });
        Thread.sleep(900);
        SwingUtilities.invokeAndWait(() -> {
        });
        write(name, frame[0].getContentPane());
        SwingUtilities.invokeAndWait(frame[0]::dispose);
    }

    private static void write(String name, java.awt.Container pane) throws Exception {
        BufferedImage[] img = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            int w = pane.getWidth();
            int h = pane.getHeight();
            img[0] = new BufferedImage((int) (w * SCALE), (int) (h * SCALE), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img[0].createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.scale(SCALE, SCALE);
            pane.paint(g);
            g.dispose();
        });
        ImageIO.write(img[0], "png", out.resolve(name + ".png").toFile());
        System.out.println("wrote " + out.resolve(name + ".png"));
    }
}
