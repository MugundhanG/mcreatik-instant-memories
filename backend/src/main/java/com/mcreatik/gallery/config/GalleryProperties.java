package com.mcreatik.gallery.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gallery")
public record GalleryProperties(
        String publicGalleryBaseUrl,
        String apiPublicBaseUrl,
        List<String> corsAllowedOrigins,
        Security security,
        Upload upload,
        UploaderSettings uploader,
        Processing processing,
        Realtime realtime,
        RateLimit rateLimit,
        Retention retention,
        Storage storage,
        FaceSearch faceSearch) {

    public record Security(String jwtSecret, Duration jwtTtl, String bootstrapAdminEmail, String bootstrapAdminPassword) {
    }

    public record Upload(long maxFileSizeBytes, Duration presignTtl, List<String> allowedMimeTypes) {
    }

    public record UploaderSettings(Duration onlineThreshold) {
    }

    public record Processing(boolean enabled, int workers, int maxAttempts, Duration staleAfter,
                             int optimizedLongEdge, float optimizedQuality,
                             int thumbnailLongEdge, float thumbnailQuality) {
    }

    public record Realtime(Duration heartbeatInterval, Duration emitterTimeout) {
    }

    public record RateLimit(int publicCapacity, int publicRefillPerSecond) {
    }

    public record Retention(int defaultDays, boolean purgeEnabled) {
    }

    /**
     * "Find my photos". {@code enabled} is the global switch (off by default). With it on and the AWS keys set, AWS
     * Rekognition is used; without keys an in-memory fake runs instead (demo and tests only).
     * {@code collectionPrefix}: collections are named {@code <prefix>-event-<eventId>} (mck-prod, mck-beta).
     */
    public record FaceSearch(boolean enabled, boolean workerEnabled, String region, String accessKeyId,
                             String secretAccessKey, String collectionPrefix, float matchThreshold, int ipPerHour,
                             int eventDailyCap, int maxAttempts, int maxFacesPerPhoto, int maxMatches) {
        public boolean hasKeys() {
            return accessKeyId != null && !accessKeyId.isBlank() && secretAccessKey != null && !secretAccessKey.isBlank();
        }
    }

    public record Storage(String type, Local local, R2 r2) {
        public record Local(String root) {
        }

        public record R2(String accountId, String accessKeyId, String secretAccessKey,
                         String originalsBucket, String mediaBucket, String mediaPublicBaseUrl) {
        }
    }

    /** Public URL guests open, e.g. https://gallery.mcreatik.com/e/arun-priya-7k3d */
    public String galleryUrl(String slug) {
        return stripTrailingSlash(publicGalleryBaseUrl) + "/e/" + slug;
    }

    public static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
