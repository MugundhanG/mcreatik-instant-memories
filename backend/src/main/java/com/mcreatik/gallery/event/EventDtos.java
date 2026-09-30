package com.mcreatik.gallery.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.mcreatik.gallery.photo.PhotoDtos;
import com.mcreatik.gallery.uploader.UploaderStatus;

public final class EventDtos {

    private EventDtos() {
    }

    public record CreateEventRequest(@NotBlank @Size(max = 200) String name,
                                     @Size(max = 80) String slug,
                                     @NotNull LocalDate eventDate,
                                     Instant startTime,
                                     Instant endTime,
                                     EventStatus status,
                                     LocalDate retentionUntil) {
    }

    /** All fields optional; only non-null fields are applied. */
    public record UpdateEventRequest(@Size(max = 200) String name,
                                     @Size(max = 80) String slug,
                                     LocalDate eventDate,
                                     Instant startTime,
                                     Instant endTime,
                                     EventStatus status,
                                     UUID coverPhotoId,
                                     LocalDate retentionUntil,
                                     Boolean clearRetention) {
    }

    public record EventSummary(UUID id, String name, String slug, LocalDate eventDate, EventStatus status,
                               String galleryUrl, long readyPhotos, Instant createdAt) {
    }

    public record EventDetail(UUID id, String name, String slug, LocalDate eventDate, Instant startTime,
                              Instant endTime, EventStatus status, UUID coverPhotoId, String coverUrl,
                              String galleryUrl, LocalDate retentionUntil, Instant createdAt, Instant updatedAt) {
    }

    public record UploaderStats(UUID id, String name, UploaderStatus status, Instant lastSeenAt,
                                String deviceIdentifier, String tokenPrefix, long photosUploaded, long photosReady,
                                int queuePending, int queueFailed, String lastError, Instant lastUploadAt) {
    }

    public record EventStats(long totalPhotos, long readyPhotos, long inProgressPhotos, long failedPhotos,
                             long storageBytes, int activeUploaders, PhotoDtos.AdminPhoto latestPhoto,
                             List<UploaderStats> uploaders) {
    }

    public record PublicEvent(String name, String slug, LocalDate eventDate, EventStatus status, boolean live,
                              String coverUrl, long photoCount) {
    }
}
