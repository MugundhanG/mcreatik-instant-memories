package com.mcreatik.gallery.storage;

import java.time.Duration;
import java.util.Optional;

/**
 * Object storage abstraction. Production uses Cloudflare R2; local development and tests use the filesystem.
 * Image binaries never go into PostgreSQL.
 */
public interface StorageService {

    /**
     * Presigned PUT of exactly {@code contentLength} bytes. The server always chooses the key. Originals go to
     * {@link StorageArea#ORIGINALS}; web/thumbnail versions prepared by the uploader go straight to {@link StorageArea#MEDIA}.
     */
    PresignedUpload presignUpload(StorageArea area, String key, String contentType, long contentLength, Duration ttl);

    default PresignedUpload presignOriginalUpload(String key, String contentType, long contentLength, Duration ttl) {
        return presignUpload(StorageArea.ORIGINALS, key, contentType, contentLength, ttl);
    }

    /** Size of a stored object, or empty if it does not exist. */
    Optional<Long> size(StorageArea area, String key);

    byte[] read(StorageArea area, String key);

    void write(StorageArea area, String key, byte[] data, String contentType);

    /** Public CDN URL for a {@link StorageArea#MEDIA} object. */
    String publicMediaUrl(String key);

    /** Short-lived signed download URL (used for originals, admin only). */
    String presignDownload(StorageArea area, String key, Duration ttl, String downloadFileName);

    void delete(StorageArea area, String key);

    void deletePrefix(StorageArea area, String prefix);
}
