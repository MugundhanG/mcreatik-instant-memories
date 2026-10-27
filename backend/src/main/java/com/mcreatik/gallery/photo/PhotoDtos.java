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
                             Instant readyAt, Instant createdAt, boolean originalAvailable, boolean hidden,
                             boolean liked) {
    }

    /** {@code nextOffset}: pass back as {@code offset} for the next page; null when there are no more. */
    public record AdminPhotoPage(List<AdminPhoto> photos, Integer nextOffset) {
    }

    /** Only the fields present are changed. */
    public record UpdatePhotoRequest(Boolean hidden, Boolean liked) {
    }

    public record PhotoFacets(List<AdminPhotoSearch.DayCount> days, long liked, long hidden) {
    }
}
