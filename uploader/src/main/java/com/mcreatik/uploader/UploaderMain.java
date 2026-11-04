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
import com.mcreatik.uploader.api.ApiModels;
import com.mcreatik.uploader.config.ConnectionCode;
import com.mcreatik.uploader.config.DeviceId;
import com.mcreatik.uploader.config.EventSwitch;
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
 * McreatiK Instant Memories Uploader.
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

    /** How this copy was started, so "Change event…" can start it again the same way. */
    private static volatile List<String> startArgs = List.of();
    private static volatile Path startDataDir;
    private static volatile int startConcurrency = 2;
    private static volatile UploadQueue startQueue;

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
                    McreatiK Instant Memories Uploader %s
                      --connect <code>     connection code from the McreatiK dashboard (MCK1....)
                      --folder <path>      folder the camera transfers photos into
                      --data-dir <path>    settings + queue location (default ~/.mcreatik-uploader)
                      --concurrency <n>    parallel uploads (default 2)
                      --ftp                receive photos from the camera over Wi-Fi (built-in FTP server)
                      --ftp-port <n>       FTP port to enter in the camera (default 2121)
                      --no-ftp             turn the built-in FTP server off
                      --upload-originals   send full-size originals for server processing instead of
                                           preparing web + thumbnail versions on this computer (slower)
                      --headless           no window; log to the console
                    """.formatted(UploadEngine.APP_VERSION));
            return;
        }
        startArgs = List.of(args);
        startDataDir = a.dataDir;
        startConcurrency = a.concurrency;
        KeepAwake.start();
        boolean headless = a.headless || GraphicsEnvironment.isHeadless();
        if (!headless) {
            com.mcreatik.uploader.ui.UiTheme.apply(); // saved Light/Dark theme before any window, so nothing flashes
        }
        Files.createDirectories(a.dataDir);
        FileLock lock = lockDataDir(a.dataDir, a.waitForLock);
        if (lock == null) {
            fail(headless, "Another McreatiK Instant Memories Uploader is already using " + a.dataDir
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
        startQueue = queue;
        FtpPhotoSource ftp = config.ftp().enabled()
                ? new FtpPhotoSource(config.watchFolder(), config.ftp().port(), config.ftp().username(), config.ftp().password())
                : null;
        PhotoSource source = ftp == null ? new FolderPhotoSource(config.watchFolder())
                : new CompositePhotoSource(List.of(new FolderPhotoSource(config.watchFolder()), ftp));
        UploadEngine engine = new UploadEngine(api, queue, source, config.concurrency(), config.deviceId(),
                !a.uploadOriginals);

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
            UploaderWindow.open(engine, config, ftp, a.dataDir, a.uploadOriginals);
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

    /**
     * Checks the code works before saving. Offline is allowed (queue until online); a bad code is not.
     *
     * @return the event details, or null when McreatiK couldn't be reached right now
     */
    public static ApiModels.Me verify(UploaderConfig config) {
        ApiModels.Me me = null;
        try {
            me = new ApiClient(config.serverUrl(), config.token()).me();
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
        return me;
    }

    /**
     * "Change event…": asks for the new event's code (keeping the folder choice and the camera's FTP login), saves it,
     * and starts the uploader again so everything comes up exactly as on a normal start.
     * Runs on the Swing thread; returns only if the user cancelled.
     */
    public static void changeEvent(UploaderConfig current) {
        Optional<UploaderConfig> next = SetupDialog.showSwitch(startDataDir, startConcurrency, current);
        if (next.isEmpty()) {
            return;
        }
        startQueue.startNewEvent(); // the new event's counts start at 0; old photos are still never re-sent
        try {
            restart();
        } catch (IOException e) {
            javax.swing.JOptionPane.showMessageDialog(null, "The new event is saved. Close the uploader and open it again"
                    + " to finish switching.\n\n(" + e.getMessage() + ")", com.mcreatik.uploader.ui.UiTheme.APP_NAME,
                    javax.swing.JOptionPane.INFORMATION_MESSAGE);
            System.exit(0);
        }
    }

    /** Starts a new copy of this app with the same options, then exits (the shutdown hook closes the queue and FTP). */
    private static void restart() throws IOException {
        List<String> command = new java.util.ArrayList<>();
        String appPath = System.getProperty("jpackage.app-path"); // set by the installed app's launcher
        if (appPath != null && Files.isExecutable(Path.of(appPath))) {
            command.add(appPath);
        } else {
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.addAll(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(UploaderMain.class.getName());
        }
        command.addAll(EventSwitch.restartArgs(startArgs));
        log.info("Restarting for the new event: " + command);
        new ProcessBuilder(command).inheritIO().start();
        System.exit(0);
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

    /** @param waitForLock after "Change event…", the previous copy may take a few seconds to close */
    private static FileLock lockDataDir(Path dataDir, boolean waitForLock) throws IOException, InterruptedException {
        FileChannel channel = FileChannel.open(dataDir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        long deadline = System.nanoTime() + (waitForLock ? 20_000_000_000L : 0);
        while (true) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (java.nio.channels.OverlappingFileLockException e) {
                lock = null;
            }
            if (lock != null || System.nanoTime() >= deadline) {
                return lock;
            }
            Thread.sleep(250);
        }
    }

    private static void fail(boolean headless, String message) {
        if (headless) {
            System.err.println(message);
            System.exit(1);
        } else {
            javax.swing.JOptionPane.showMessageDialog(null, message, com.mcreatik.uploader.ui.UiTheme.APP_NAME,
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
        boolean uploadOriginals;
        boolean waitForLock;

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
                    case "--upload-originals" -> a.uploadOriginals = true;
                    case "--wait-for-lock" -> a.waitForLock = true;
                    case "--help", "-h" -> a.help = true;
                    default -> throw new IllegalArgumentException("Unknown option " + args[i] + " (try --help)");
                }
            }
            return a;
        }
    }
}
