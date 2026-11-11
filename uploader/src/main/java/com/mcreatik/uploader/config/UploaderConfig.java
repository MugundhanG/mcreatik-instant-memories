package com.mcreatik.uploader.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;

/**
 * Persistent uploader settings, stored as {@code config.properties} in the data directory.
 * One data directory = one uploader identity = one queue. Run several instances on one laptop
 * (one per camera) by giving each its own {@code --data-dir}.
 */
public record UploaderConfig(String serverUrl, String token, Path watchFolder, int concurrency, String deviceId,
                             Ftp ftp, Session session) {

    /**
     * The studio sign-in kept for "Change event…": the website's login token (valid 12 hours). The password is never
     * stored.
     */
    public record Session(String email, String token, Instant expiresAt) {

        public boolean valid(Instant now) {
            return token != null && expiresAt != null && now.isBefore(expiresAt);
        }

        /** Never print the token. */
        @Override
        public String toString() {
            return "Session[email=" + email + ", expiresAt=" + expiresAt + "]";
        }
    }

    public static final String FILE_NAME = "config.properties";

    /**
     * Built-in camera FTP server. Port 2121 by default: port 21 needs admin rights on macOS/Linux, and the
     * camera lets you enter any port. The password is short and unambiguous because it is typed on a camera.
     */
    public record Ftp(boolean enabled, int port, String username, String password) {

        public static final int DEFAULT_PORT = 2121;

        public static Ftp generate(boolean enabled) {
            return new Ftp(enabled, DEFAULT_PORT, "mcreatik", Passwords.cameraFriendly(8));
        }

        public Ftp withEnabled(boolean on) {
            return new Ftp(on, port, username, password);
        }

        public Ftp withPort(int newPort) {
            return new Ftp(enabled, newPort, username, password);
        }
    }

    /** Connected with a connection code: no studio sign-in. */
    public UploaderConfig(String serverUrl, String token, Path watchFolder, int concurrency, String deviceId, Ftp ftp) {
        this(serverUrl, token, watchFolder, concurrency, deviceId, ftp, null);
    }

    public UploaderConfig {
        if (concurrency < 1 || concurrency > 8) {
            concurrency = 2;
        }
        serverUrl = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        if (ftp == null) {
            ftp = Ftp.generate(false);
        }
    }

    public UploaderConfig withWatchFolder(Path folder) {
        return new UploaderConfig(serverUrl, token, folder, concurrency, deviceId, ftp, session);
    }

    public UploaderConfig withSession(Session newSession) {
        return new UploaderConfig(serverUrl, token, watchFolder, concurrency, deviceId, ftp, newSession);
    }

    public UploaderConfig withFtp(Ftp newFtp) {
        return new UploaderConfig(serverUrl, token, watchFolder, concurrency, deviceId, newFtp, session);
    }

    public static Optional<UploaderConfig> load(Path dataDir) throws IOException {
        Path file = dataDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        String server = p.getProperty("server");
        String token = p.getProperty("token");
        String folder = p.getProperty("watchFolder");
        if (server == null || token == null || folder == null) {
            return Optional.empty();
        }
        Ftp ftp = p.getProperty("ftpPassword") == null ? Ftp.generate(false)
                : new Ftp(Boolean.parseBoolean(p.getProperty("ftpEnabled", "false")),
                        Integer.parseInt(p.getProperty("ftpPort", Integer.toString(Ftp.DEFAULT_PORT))),
                        p.getProperty("ftpUser", "mcreatik"), p.getProperty("ftpPassword"));
        Session session = null;
        try {
            if (p.getProperty("sessionToken") != null && p.getProperty("sessionExpiresAt") != null) {
                session = new Session(p.getProperty("sessionEmail", ""), p.getProperty("sessionToken"),
                        Instant.parse(p.getProperty("sessionExpiresAt")));
            }
        } catch (java.time.format.DateTimeParseException ignored) {
            // an unreadable expiry: treat as signed out
        }
        return Optional.of(new UploaderConfig(server, token, Path.of(folder),
                Integer.parseInt(p.getProperty("concurrency", "2")),
                p.getProperty("deviceId", DeviceId.generate()), ftp, session));
    }

    public void save(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        Properties p = new Properties();
        p.setProperty("server", serverUrl);
        p.setProperty("token", token);
        p.setProperty("watchFolder", watchFolder.toAbsolutePath().toString());
        p.setProperty("concurrency", Integer.toString(concurrency));
        p.setProperty("deviceId", deviceId);
        p.setProperty("ftpEnabled", Boolean.toString(ftp.enabled()));
        p.setProperty("ftpPort", Integer.toString(ftp.port()));
        p.setProperty("ftpUser", ftp.username());
        p.setProperty("ftpPassword", ftp.password());
        if (session != null && session.token() != null && session.expiresAt() != null) {
            p.setProperty("sessionEmail", session.email() == null ? "" : session.email());
            p.setProperty("sessionToken", session.token());
            p.setProperty("sessionExpiresAt", session.expiresAt().toString());
        }
        Path tmp = dataDir.resolve(FILE_NAME + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            p.store(out, "McreatiK Instant Memories Uploader - keep this file private (contains the uploader token and the sign-in token, never the password)");
        }
        Files.move(tmp, dataDir.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        restrictPermissions(dataDir.resolve(FILE_NAME));
    }

    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows: rely on the user profile directory ACLs.
        }
    }

    /** Never print the token. */
    @Override
    public String toString() {
        return "UploaderConfig[server=" + serverUrl + ", watchFolder=" + watchFolder + ", concurrency=" + concurrency
                + ", ftp=" + (ftp.enabled() ? "port " + ftp.port() : "off") + "]";
    }
}
