package com.mcreatik.uploader;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

import com.mcreatik.uploader.api.ApiClient;
import com.mcreatik.uploader.api.ApiException;
import com.mcreatik.uploader.config.ConnectionCode;
import com.mcreatik.uploader.config.DeviceId;
import com.mcreatik.uploader.config.UploaderConfig;
import com.mcreatik.uploader.engine.EngineStatus;
import com.mcreatik.uploader.engine.UploadEngine;
import com.mcreatik.uploader.queue.UploadQueue;
import com.mcreatik.uploader.source.FolderPhotoSource;
import com.mcreatik.uploader.ui.SetupDialog;
import com.mcreatik.uploader.ui.UploaderWindow;

/**
 * McreatiK Uploader.
 *
 * <pre>
 *   java -jar mcreatik-uploader.jar                                  # window; asks for code + folder the first time
 *   java -jar mcreatik-uploader.jar --connect MCK1.xxx --folder D:\Camera1
 *   java -jar mcreatik-uploader.jar --headless --data-dir ~/.mcreatik-camera2
 * </pre>
 * One data directory = one uploader (one camera). Run one instance per camera, each with its own data dir.
 */
public final class UploaderMain {

    private static final Logger log = Logger.getLogger(UploaderMain.class.getName());

    private UploaderMain() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tT %4$s %5$s%6$s%n");
        Args a = Args.parse(args);
        if (a.help) {
            System.out.println("""
                    McreatiK Uploader %s
                      --connect <code>     connection code from the McreatiK dashboard (MCK1....)
                      --folder <path>      folder the camera transfers photos into
                      --data-dir <path>    settings + queue location (default ~/.mcreatik-uploader)
                      --concurrency <n>    parallel uploads (default 2)
                      --headless           no window; log to the console
                    """.formatted(UploadEngine.APP_VERSION));
            return;
        }
        boolean headless = a.headless || GraphicsEnvironment.isHeadless();
        Files.createDirectories(a.dataDir);
        FileLock lock = lockDataDir(a.dataDir);
        if (lock == null) {
            fail(headless, "Another McreatiK Uploader is already using " + a.dataDir
                    + ". To run a second camera on this computer, start it with a different --data-dir.");
            return;
        }

        UploaderConfig config = resolveConfig(a, headless);
        if (config == null) {
            return;
        }

        ApiClient api = new ApiClient(config.serverUrl(), config.token());
        UploadQueue queue = new UploadQueue(a.dataDir.resolve("queue.db"));
        FolderPhotoSource source = new FolderPhotoSource(config.watchFolder());
        UploadEngine engine = new UploadEngine(api, queue, source, config.concurrency(), config.deviceId());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            engine.close();
            queue.close();
        }, "shutdown"));
        engine.start();

        if (headless) {
            runConsole(engine);
        } else {
            UploaderWindow.open(engine, a.dataDir, config);
        }
    }

    private static UploaderConfig resolveConfig(Args a, boolean headless) throws IOException {
        Optional<UploaderConfig> existing = UploaderConfig.load(a.dataDir);
        if (a.connect != null || a.folder != null) {
            String server;
            String token;
            if (a.connect != null) {
                ConnectionCode code = ConnectionCode.parse(a.connect);
                server = code.server();
                token = code.token();
            } else if (existing.isPresent()) {
                server = existing.get().serverUrl();
                token = existing.get().token();
            } else {
                fail(headless, "--connect <code> is required the first time.");
                return null;
            }
            Path folder = a.folder != null ? Path.of(a.folder)
                    : existing.map(UploaderConfig::watchFolder).orElse(null);
            if (folder == null) {
                fail(headless, "--folder <path> is required the first time.");
                return null;
            }
            String deviceId = existing.map(UploaderConfig::deviceId).orElseGet(DeviceId::generate);
            UploaderConfig config = new UploaderConfig(server, token, folder, a.concurrency, deviceId);
            verify(config);
            config.save(a.dataDir);
            return config;
        }
        if (existing.isPresent()) {
            return existing.get();
        }
        if (headless) {
            fail(true, "Not set up yet. Run once with --connect <code> --folder <path>.");
            return null;
        }
        return SetupDialog.show(a.dataDir, a.concurrency).orElse(null);
    }

    /** Checks the code works before saving. Offline is allowed (queue until online); a bad code is not. */
    public static void verify(UploaderConfig config) {
        try {
            var me = new ApiClient(config.serverUrl(), config.token()).me();
            log.info("Connected as \"" + me.uploaderName() + "\" for event \"" + me.eventName() + "\"");
        } catch (ApiException e) {
            if (e.kind() == ApiException.Kind.TRANSIENT) {
                log.warning("Could not reach McreatiK right now (" + e.getMessage()
                        + "). Photos will be queued and uploaded when the connection is back.");
            } else {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }
        if (!Files.isDirectory(config.watchFolder())) {
            throw new IllegalArgumentException("Folder does not exist: " + config.watchFolder());
        }
    }

    private static void runConsole(UploadEngine engine) throws InterruptedException {
        CountDownLatch forever = new CountDownLatch(1);
        Thread.ofPlatform().daemon(true).name("console-status").start(() -> {
            String previous = "";
            while (true) {
                EngineStatus s = engine.status();
                String line = "[%s] %s | waiting %d, uploading %d, uploaded %d, duplicates %d, failed %d%s".formatted(
                        s.connection(), s.eventName() == null ? "…" : s.eventName(), s.counts().queued(),
                        s.counts().uploading(), s.counts().done(), s.counts().duplicate(), s.counts().failed(),
                        s.blockedReason() == null ? "" : " | PAUSED: " + s.blockedReason());
                if (!line.equals(previous)) {
                    System.out.println(line);
                    previous = line;
                }
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        forever.await();
    }

    private static FileLock lockDataDir(Path dataDir) throws IOException {
        FileChannel channel = FileChannel.open(dataDir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        return channel.tryLock();
    }

    private static void fail(boolean headless, String message) {
        if (headless) {
            System.err.println(message);
            System.exit(1);
        } else {
            javax.swing.JOptionPane.showMessageDialog(null, message, "McreatiK Uploader",
                    javax.swing.JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    static final class Args {
        String connect;
        String folder;
        Path dataDir = Path.of(System.getProperty("user.home"), ".mcreatik-uploader");
        int concurrency = 2;
        boolean headless;
        boolean help;

        static Args parse(String[] args) {
            Args a = new Args();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--connect" -> a.connect = args[++i];
                    case "--folder" -> a.folder = args[++i];
                    case "--data-dir" -> a.dataDir = Path.of(args[++i]);
                    case "--concurrency" -> a.concurrency = Integer.parseInt(args[++i]);
                    case "--headless" -> a.headless = true;
                    case "--help", "-h" -> a.help = true;
                    default -> throw new IllegalArgumentException("Unknown option " + args[i] + " (try --help)");
                }
            }
            return a;
        }
    }
}
