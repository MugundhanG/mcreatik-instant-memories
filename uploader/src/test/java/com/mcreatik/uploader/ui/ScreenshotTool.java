package com.mcreatik.uploader.ui;

import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import com.mcreatik.uploader.Cameras;
import com.mcreatik.uploader.FakeMcreatikServer;
import com.mcreatik.uploader.TestPhotos;
import com.mcreatik.uploader.config.UploaderConfig;
import com.mcreatik.uploader.ui.theme.Theme;

/**
 * Renders the setup and Live screens with demo data into PNGs (no network, no real accounts). Run under Xvfb:
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

        SwingUtilities.invokeAndWait(() -> Theme.switchTo(Theme.LIGHT));
        setupDialog("01-setup-code-light");
        livePage("02-live-light");
        System.exit(0);
    }

    /** The real setup dialog (connection-code step), grabbed while it is open. */
    private static void setupDialog(String name) throws Exception {
        Path data = Files.createTempDirectory("shot-setup");
        Thread t = new Thread(() -> SetupDialog.show(data, 2, true), "setup-shot");
        t.setDaemon(true);
        t.start();
        Thread.sleep(2500);
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w instanceof javax.swing.JDialog d && d.isShowing()) {
                write(name, d.getContentPane());
                SwingUtilities.invokeAndWait(d::dispose);
            }
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
