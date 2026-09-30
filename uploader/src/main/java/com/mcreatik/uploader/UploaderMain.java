package com.mcreatik.uploader;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
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
import com.mcreatik.uploader.source.CompositePhotoSource;
import com.mcreatik.uploader.source.FolderPhotoSource;
import com.mcreatik.uploader.source.FtpPhotoSource;
import com.mcreatik.uploader.source.PhotoSource;
import com.mcreatik.uploader.ui.SetupDialog;
import com.mcreatik.uploader.ui.UploaderWindow;

/**
 * McreatiK Uploader.
 *
 * <pre>
 *   java -jar mcreatik-uploader.jar                                  # window; asks for code + folder the first time
 *   java -jar mcreatik-uploader.jar --connect MCK1.xxx --folder D:\Camera1
 *   java -jar mcreatik-uploader.jar --headless --data-dir ~/.mcreatik-camera2
 *   java -jar mcreatik-uploader.jar --ftp                            # camera sends over Wi-Fi (FTP) to this laptop
 * </pre>
 * One data directory = one uploader (one camera). Run one instance per camera, each with its own data dir.
 */
public final class UploaderMain {

    private static final Logger log = Logger.getLogger(UploaderMain.class.getName());
    /**
     * The embedded FTP server logs every command at INFO; photographers only need problems.
     * Held in a static field because java.util.logging keeps loggers (and their levels) only weakly.
     */
    private static final List<Logger> QUIET_LOGGERS =
            List.of(Logger.getLogger("org.apache.ftpserver"), Logger.getLogger("org.apache.mina"));

    private UploaderMain() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tT %4$s %5$s%6$s%n");
        for (Logger quiet : QUIET_LOGGERS) {
            quiet.setLevel(java.util.logging.Level.WARNING);
        }
        Args a = Args.parse(args);
        if (a.help) {
            System.out.println("""
                    McreatiK Uploader %s
                      --connect <code>     connection code from the McreatiK dashboard (MCK1....)
                      --folder <path>      folder the camera transfers photos into
                      --data-dir <path>    settings + queue location (default ~/.mcreatik-uploader)
                      --concurrency <n>    parallel uploads (default 2)
                      --ftp                receive photos from the camera over Wi-Fi (built-in FTP server)
                      --ftp-port <n>       FTP port to enter in the camera (default 2121)
                      --no-ftp             turn the built-in FTP server off
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

        config = applyFtpFlags(config, a);
        config.save(a.dataDir); // persists generated FTP credentials so they never change between runs

        ApiClient api = new ApiClient(config.serverUrl(), config.token());
        UploadQueue queue = new UploadQueue(a.dataDir.resolve("queue.db"));
        FtpPhotoSource ftp = config.ftp().enabled()
                ? new FtpPhotoSource(config.watchFolder(), config.ftp().port(), config.ftp().username(), config.ftp().password())
                : null;
        PhotoSource source = ftp == null ? new FolderPhotoSource(config.watchFolder())
                : new CompositePhotoSource(List.of(new FolderPhotoSource(config.watchFolder()), ftp));
        UploadEngine engine = new UploadEngine(api, queue, source, config.concurrency(), config.deviceId());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            engine.close();
            queue.close();
        }, "shutdown"));
        engine.start();

        if (headless) {
            if (ftp != null) {
                printFtpSettings(ftp.status());
            }
            runConsole(engine);
        } else {
            UploaderWindow.open(engine, config, ftp);
        }
    }

    private static UploaderConfig applyFtpFlags(UploaderConfig config, Args a) {
        UploaderConfig.Ftp ftp = config.ftp();
        if (a.ftp != null) {
            ftp = ftp.withEnabled(a.ftp);
        }
        if (a.ftpPort != null) {
            ftp = ftp.withPort(a.ftpPort);
        }
        return config.withFtp(ftp);
    }

    private static void printFtpSettings(FtpPhotoSource.Status s) {
        System.out.println("""
                ---- Camera FTP settings (enter these in the camera) ----
                  Server address : %s
                  Port           : %d
                  User name      : %s
                  Password       : %s
                  Passive mode   : on or off (both work)
                  Camera and this computer must be on the same Wi-Fi / hotspot.
                ---------------------------------------------------------""".formatted(
                s.addresses().isEmpty() ? "(no network found)" : String.join("  or  ", s.addresses()),
                s.port(), s.username(), s.password()));
        if (s.error() != null) {
            System.out.println("  ERROR: " + s.error());
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
            UploaderConfig.Ftp ftp = existing.map(UploaderConfig::ftp).orElseGet(() -> UploaderConfig.Ftp.generate(false));
            UploaderConfig config = applyFtpFlags(new UploaderConfig(server, token, folder, a.concurrency, deviceId, ftp), a);
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
        return SetupDialog.show(a.dataDir, a.concurrency, a.ftp == null || a.ftp).orElse(null);
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
            if (!config.ftp().enabled()) {
                throw new IllegalArgumentException("Folder does not exist: " + config.watchFolder());
            }
            try {
                Files.createDirectories(config.watchFolder()); // FTP mode: we own this folder
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot create folder " + config.watchFolder() + ": " + e.getMessage(), e);
            }
        }
    }

    private static void runConsole(UploadEngine engine) throws InterruptedException {
        CountDownLatch forever = new CountDownLatch(1);
        Thread.ofPlatform().daemon(true).name("console-status").start(() -> {
            String previous = "";
            while (true) {
                EngineStatus s = engine.status();
                String line = "[%s] %s | waiting %d, uploading %d, uploaded %d, duplicates %d, failed %d%s".formatted(
                        s.connection(), s.eventName() == null ? "(event not loaded yet)" : s.eventName(), s.counts().queued(),
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
        Boolean ftp;
        Integer ftpPort;

        static Args parse(String[] args) {
            Args a = new Args();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--connect" -> a.connect = args[++i];
                    case "--folder" -> a.folder = args[++i];
                    case "--data-dir" -> a.dataDir = Path.of(args[++i]);
                    case "--concurrency" -> a.concurrency = Integer.parseInt(args[++i]);
                    case "--headless" -> a.headless = true;
                    case "--ftp" -> a.ftp = true;
                    case "--no-ftp" -> a.ftp = false;
                    case "--ftp-port" -> a.ftpPort = Integer.parseInt(args[++i]);
                    case "--help", "-h" -> a.help = true;
                    default -> throw new IllegalArgumentException("Unknown option " + args[i] + " (try --help)");
                }
            }
            return a;
        }
    }
}
