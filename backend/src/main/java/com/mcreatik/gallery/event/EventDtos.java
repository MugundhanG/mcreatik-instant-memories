package com.mcreatik.gallery.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.mcreatik.gallery.auth.StudioBranding;
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
                                     LocalDate retentionUntil,
                                     /* super admin only: create the event for this studio */
                                     UUID ownerId,
                                     /* MAIN (default) or QUICK */
                                     EventType eventType) {
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

    /** {@code ownerName}: the studio, shown to the super admin. */
    public record EventSummary(UUID id, String name, String slug, LocalDate eventDate, EventStatus status,
                               String galleryUrl, long readyPhotos, long guestPhotos, long clientPhotos, Instant createdAt,
                               UUID ownerId, String ownerName, EventType eventType) {
    }

    public record EventDetail(UUID id, String name, String slug, LocalDate eventDate, Instant startTime,
                              Instant endTime, EventStatus status, UUID coverPhotoId, String coverUrl,
                              String galleryUrl, LocalDate retentionUntil, Instant createdAt, Instant updatedAt,
                              UUID ownerId, String ownerName, StudioBranding.Brand brand,
                              boolean liveGalleryEnabled, boolean clientGalleryEnabled, EventType eventType,
                              LocalDate activeUntil, int storageQuotaGb, long storageBytes, boolean storageLocked,
                              boolean expired, String liveGalleryBlocked, String clientGalleryBlocked) {
    }

    /**
     * Turn the guests' gallery and/or the couple's gallery on or off (the studio on its own events, McreatiK on any).
     * Null leaves it as is.
     */
    public record GalleryAccessRequest(Boolean liveGallery, Boolean clientGallery) {
    }

    /** The camera itself, apart from the laptop: CONNECTED, NOT_CONNECTED, or UNKNOWN (an older uploader app). */
    public enum CameraState { CONNECTED, NOT_CONNECTED, UNKNOWN }

    public record UploaderStats(UUID id, String name, UploaderStatus status, Instant lastSeenAt,
                                String deviceIdentifier, String tokenPrefix, long photosUploaded, long photosReady,
                                int queuePending, int queueFailed, String lastError, Instant lastUploadAt,
                                boolean paused, CameraState camera, Instant cameraLastPhotoAt) {
    }

    /**
     * {@code guestPhotos} / {@code clientPhotos}: READY photos in each gallery (what the studio uploaded, hidden ones
     * included); {@code readyPhotos} is their sum. {@code totalPhotos} counts every row, also unfinished and failed
     * ones, so it is never shown as "the photo count".
     */
    public record EventStats(long totalPhotos, long readyPhotos, long inProgressPhotos, long failedPhotos,
                             long storageBytes, int activeUploaders, PhotoDtos.AdminPhoto latestPhoto,
                             List<UploaderStats> uploaders, long hiddenPhotos, long likedPhotos, long guestPhotos,
                             long clientPhotos) {
    }

    public record PublicEvent(String name, String slug, LocalDate eventDate, EventStatus status, boolean live,
                              String coverUrl, long photoCount, StudioBranding.Brand brand,
                              boolean faceSearch) {
    }
}
