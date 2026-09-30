package com.mcreatik.gallery.photo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PhotoDtos {

    private PhotoDtos() {
    }

    /** What guests see. No uploader ids, no storage keys, no original URLs. */
    public record PublicPhoto(UUID id, String thumbnailUrl, String webUrl, Integer width, Integer height,
                              Instant readyAt, String cursor) {
    }

    public record PublicPhotoPage(List<PublicPhoto> photos, String nextCursor) {
    }

    public record AdminPhoto(UUID id, UUID uploaderId, String originalFileName, PhotoStatus status,
                             String failureReason, long fileSize, Integer width, Integer height,
                             String thumbnailUrl, String webUrl, Instant capturedAt, Instant uploadedAt,
                             Instant readyAt, Instant createdAt) {
    }

    public record AdminPhotoPage(List<AdminPhoto> photos, String nextCursor) {
    }
}
