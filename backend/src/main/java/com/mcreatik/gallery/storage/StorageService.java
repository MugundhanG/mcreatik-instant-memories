package com.mcreatik.gallery.storage;

import java.time.Duration;
import java.util.Optional;

/**
 * Object storage abstraction. Production uses Cloudflare R2; local development and tests use the filesystem.
 * Image binaries never go into PostgreSQL.
 */
public interface StorageService {

    /** Presigned PUT into {@link StorageArea#ORIGINALS}. The server always chooses the key. */
    PresignedUpload presignOriginalUpload(String key, String contentType, long contentLength, Duration ttl);

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
