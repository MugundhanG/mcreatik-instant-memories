package com.mcreatik.uploader.source;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.ftpserver.ConnectionConfigFactory;
import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.ftplet.DefaultFtplet;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpFile;
import org.apache.ftpserver.ftplet.FtpReply;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.ftplet.FtpSession;
import org.apache.ftpserver.ftplet.FtpletResult;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.ClearTextPasswordEncryptor;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission;
import org.apache.ftpserver.usermanager.impl.WritePermission;

/**
 * Built-in FTP server: the camera (e.g. Canon EOS R6 Mark II "FTP transfer" over Wi-Fi) sends each photo
 * straight to this laptop. No separate FTP software needed.
 *
 * <p>Files are received into a hidden {@code .incoming} folder and only moved into the watched photo folder
 * after the transfer completed successfully (FTP reply 226). A Wi-Fi drop mid-file therefore never produces a
 * truncated photo; the camera simply re-sends it. Completed photos are handed to the uploader immediately,
 * without waiting for the next folder scan.
 *
 * <p>Plain FTP on the local network only (camera ↔ laptop on the same Wi-Fi/hotspot); the internet upload
 * to McreatiK is HTTPS.
 */
public class FtpPhotoSource implements PhotoSource {

    private static final Logger log = Logger.getLogger(FtpPhotoSource.class.getName());
    public static final String INCOMING_DIR = ".incoming";
    private static final Set<String> UPLOAD_COMMANDS = Set.of("STOR", "APPE");

    private final Path photoFolder;
    private final Path incoming;
    private final int port;
    private final String username;
    private final String password;
    private final String passivePorts;

    private FtpServer server;
    private Consumer<Path> consumer;

    private final AtomicInteger filesReceived = new AtomicInteger();
    private volatile Instant lastFileAt;
    private volatile String lastClient;
    private volatile String error;

    public FtpPhotoSource(Path photoFolder, int port, String username, String password) {
        this(photoFolder, port, username, password, "50000-50100");
    }

    public FtpPhotoSource(Path photoFolder, int port, String username, String password, String passivePorts) {
        this.photoFolder = photoFolder.toAbsolutePath().normalize();
        this.incoming = this.photoFolder.resolve(INCOMING_DIR);
        this.port = port;
        this.username = username;
        this.password = password;
        this.passivePorts = passivePorts;
    }

    @Override
    public void start(Consumer<Path> onPhoto) {
        this.consumer = onPhoto;
        try {
            Files.createDirectories(incoming);
            server = createServer();
            server.start();
            error = null;
            log.info("Camera FTP server listening on port " + port + " (user " + username + ")");
        } catch (IOException | FtpException | RuntimeException e) {
            // Never take the uploader down: folder watching keeps working, the UI shows the problem.
            error = "Camera FTP server could not start on port " + port + ": " + rootMessage(e);
            log.log(Level.SEVERE, error, e);
        }
    }

    private FtpServer createServer() throws FtpException {
        FtpServerFactory factory = new FtpServerFactory();

        ListenerFactory listener = new ListenerFactory();
        listener.setPort(port);
        listener.setIdleTimeout(600); // cameras keep the session open between shots
        DataConnectionConfigurationFactory data = new DataConnectionConfigurationFactory();
        data.setPassivePorts(passivePorts);
        data.setActiveEnabled(true); // Canon lets the photographer switch passive mode off
        data.setActiveIpCheck(false);
        listener.setDataConnectionConfiguration(data.createDataConnectionConfiguration());
        factory.addListener("default", listener.createListener());

        ConnectionConfigFactory connection = new ConnectionConfigFactory();
        connection.setAnonymousLoginEnabled(false);
        connection.setMaxLogins(10);
        connection.setMaxLoginFailures(5);
        connection.setLoginFailureDelay(500);
        factory.setConnectionConfig(connection.createConnectionConfig());

        PropertiesUserManagerFactory users = new PropertiesUserManagerFactory();
        users.setPasswordEncryptor(new ClearTextPasswordEncryptor()); // in-memory only, never written to disk
        var userManager = users.createUserManager();
        BaseUser user = new BaseUser();
        user.setName(username);
        user.setPassword(password);
        user.setHomeDirectory(incoming.toString());
        user.setEnabled(true);
        user.setMaxIdleTime(0);
        user.setAuthorities(List.<Authority>of(new WritePermission(), new ConcurrentLoginPermission(10, 10)));
        userManager.save(user);
        factory.setUserManager(userManager);

        // Must be mutable: the server clears this map when it stops.
        Map<String, org.apache.ftpserver.ftplet.Ftplet> ftplets = new java.util.HashMap<>();
        ftplets.put("mcreatik", new DefaultFtplet() {
            @Override
            public FtpletResult afterCommand(FtpSession session, FtpRequest request, FtpReply reply)
                    throws FtpException, IOException {
                String command = request.getCommand().toUpperCase();
                if (UPLOAD_COMMANDS.contains(command) && reply.getCode() == FtpReply.REPLY_226_CLOSING_DATA_CONNECTION) {
                    onUploadComplete(session, request.getArgument());
                }
                return super.afterCommand(session, request, reply);
            }
        });
        factory.setFtplets(ftplets);
        return factory.createServer();
    }

    private void onUploadComplete(FtpSession session, String argument) {
        try {
            FtpFile ftpFile = session.getFileSystemView().getFile(argument);
            if (!(ftpFile.getPhysicalFile() instanceof File physical)) {
                return;
            }
            Path received = physical.toPath().toAbsolutePath().normalize();
            if (!received.startsWith(incoming) || !Files.isRegularFile(received)) {
                return;
            }
            Path relative = incoming.relativize(received);
            Path target = publish(received, photoFolder.resolve(relative).normalize());
            filesReceived.incrementAndGet();
            lastFileAt = Instant.now();
            if (session.getClientAddress() instanceof InetSocketAddress client) {
                lastClient = client.getAddress().getHostAddress();
            }
            if (target != null && consumer != null) {
                consumer.accept(target);
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Could not take over received file " + argument + ": " + e.getMessage(), e);
        }
    }

    /**
     * Moves a completed file into the photo folder. Identical re-sends (cameras re-send after reconnecting)
     * are dropped; a different photo with the same name gets a numeric suffix. Returns the published path,
     * or null when nothing new was published.
     */
    Path publish(Path received, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            if (Files.mismatch(received, target) == -1) {
                Files.delete(received);
                return null;
            }
            target = freeName(target);
        }
        try {
            Files.move(received, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Different volume: copy under a hidden name (ignored by the folder scanner), then rename atomically.
            Path tmp = target.resolveSibling(".mcreatik-" + target.getFileName() + ".tmp");
            Files.copy(received, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            Files.delete(received);
        }
        return target;
    }

    private static Path freeName(Path target) {
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            Path candidate = target.resolveSibling(base + "_" + i + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    public Status status() {
        return new Status(server != null && !server.isStopped(), port, username, password, NetworkAddresses.lan(),
                filesReceived.get(), lastFileAt, lastClient, error);
    }

    /** What the photographer types into the camera, plus live receive state. */
    public record Status(boolean running, int port, String username, String password, List<String> addresses,
                         int filesReceived, Instant lastFileAt, String lastClient, String error) {
    }

    @Override
    public String describe() {
        return "camera FTP on port " + port;
    }

    @Override
    public void close() {
        if (server != null && !server.isStopped()) {
            server.stop();
        }
    }
}
