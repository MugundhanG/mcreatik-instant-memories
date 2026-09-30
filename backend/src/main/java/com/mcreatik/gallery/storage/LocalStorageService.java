package com.mcreatik.gallery.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.mcreatik.gallery.config.GalleryProperties;

/**
 * Filesystem storage for local development, tests and the end-to-end simulation. It mimics R2 closely:
 * uploads use short-lived HMAC-signed PUT URLs served by {@link LocalStorageController}.
 */
@Service
@ConditionalOnProperty(name = "gallery.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalStorageService implements StorageService {

    private final Path root;
    private final String baseUrl;
    private final byte[] signingKey = new byte[32];

    public LocalStorageService(GalleryProperties properties) {
        this.root = Path.of(properties.storage().local().root()).toAbsolutePath().normalize();
        this.baseUrl = GalleryProperties.stripTrailingSlash(properties.apiPublicBaseUrl()) + "/local-storage";
        new SecureRandom().nextBytes(signingKey);
    }

    Path resolve(StorageArea area, String key) {
        Path path = root.resolve(area.name().toLowerCase()).resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Invalid key");
        }
        return path;
    }

    @Override
    public PresignedUpload presignOriginalUpload(String key, String contentType, long contentLength, Duration ttl) {
        Instant expires = Instant.now().plus(ttl);
        String sig = sign("PUT", key, expires.getEpochSecond(), contentType, contentLength);
        String url = baseUrl + "/upload/" + key + "?expires=" + expires.getEpochSecond()
                + "&length=" + contentLength + "&sig=" + sig;
        return new PresignedUpload(url, "PUT", Map.of("Content-Type", contentType), expires);
    }

    boolean verifyUpload(String key, long expires, String contentType, long length, String sig) {
        return Instant.now().getEpochSecond() <= expires
                && sig != null
                && MessageDigest.isEqual(sign("PUT", key, expires, contentType, length).getBytes(StandardCharsets.UTF_8),
                        sig.getBytes(StandardCharsets.UTF_8));
    }

    boolean verifyDownload(String key, long expires, String sig) {
        return Instant.now().getEpochSecond() <= expires
                && sig != null
                && MessageDigest.isEqual(sign("GET", key, expires, "", 0).getBytes(StandardCharsets.UTF_8),
                        sig.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String method, String key, long expires, String contentType, long length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            String payload = method + "\n" + key + "\n" + expires + "\n" + contentType + "\n" + length;
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<Long> size(StorageArea area, String key) {
        Path path = resolve(area, key);
        try {
            return Files.exists(path) ? Optional.of(Files.size(path)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] read(StorageArea area, String key) {
        try {
            return Files.readAllBytes(resolve(area, key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void write(StorageArea area, String key, byte[] data, String contentType) {
        Path path = resolve(area, key);
        try {
            Files.createDirectories(path.getParent());
            Path tmp = Files.createTempFile(path.getParent(), ".upload", ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void writeStream(StorageArea area, String key, java.io.InputStream in, long maxBytes) throws IOException {
        Path path = resolve(area, key);
        Files.createDirectories(path.getParent());
        Path tmp = Files.createTempFile(path.getParent(), ".upload", ".tmp");
        try {
            long copied = Files.copy(new LimitedInputStream(in, maxBytes), tmp, StandardCopyOption.REPLACE_EXISTING);
            if (copied != maxBytes) {
                throw new IOException("Incomplete upload: " + copied + " of " + maxBytes + " bytes");
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Override
    public String publicMediaUrl(String key) {
        return baseUrl + "/media/" + key;
    }

    @Override
    public String presignDownload(StorageArea area, String key, Duration ttl, String downloadFileName) {
        long expires = Instant.now().plus(ttl).getEpochSecond();
        return baseUrl + "/originals/" + key + "?expires=" + expires + "&sig=" + sign("GET", key, expires, "", 0)
                + "&name=" + URLEncoder.encode(downloadFileName == null ? "photo.jpg" : downloadFileName, StandardCharsets.UTF_8);
    }

    @Override
    public void delete(StorageArea area, String key) {
        try {
            Files.deleteIfExists(resolve(area, key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void deletePrefix(StorageArea area, String prefix) {
        Path dir = resolve(area, prefix);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Refuses to read past the signed content length. */
    private static final class LimitedInputStream extends java.io.FilterInputStream {
        private long remaining;

        LimitedInputStream(java.io.InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = super.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = super.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }
    }
}
