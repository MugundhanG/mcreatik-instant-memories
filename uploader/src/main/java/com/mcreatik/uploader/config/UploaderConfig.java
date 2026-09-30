package com.mcreatik.uploader.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;

/**
 * Persistent uploader settings, stored as {@code config.properties} in the data directory.
 * One data directory = one uploader identity = one queue. Run several instances on one laptop
 * (one per camera) by giving each its own {@code --data-dir}.
 */
public record UploaderConfig(String serverUrl, String token, Path watchFolder, int concurrency, String deviceId) {

    public static final String FILE_NAME = "config.properties";

    public UploaderConfig {
        if (concurrency < 1 || concurrency > 8) {
            concurrency = 2;
        }
        serverUrl = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
    }

    public UploaderConfig withWatchFolder(Path folder) {
        return new UploaderConfig(serverUrl, token, folder, concurrency, deviceId);
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
        return Optional.of(new UploaderConfig(server, token, Path.of(folder),
                Integer.parseInt(p.getProperty("concurrency", "2")),
                p.getProperty("deviceId", DeviceId.generate())));
    }

    public void save(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        Properties p = new Properties();
        p.setProperty("server", serverUrl);
        p.setProperty("token", token);
        p.setProperty("watchFolder", watchFolder.toAbsolutePath().toString());
        p.setProperty("concurrency", Integer.toString(concurrency));
        p.setProperty("deviceId", deviceId);
        Path tmp = dataDir.resolve(FILE_NAME + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            p.store(out, "McreatiK Uploader - keep this file private (contains the uploader token)");
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
        return "UploaderConfig[server=" + serverUrl + ", watchFolder=" + watchFolder + ", concurrency=" + concurrency + "]";
    }
}
