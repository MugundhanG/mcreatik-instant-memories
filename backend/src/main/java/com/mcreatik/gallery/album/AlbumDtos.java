package com.mcreatik.gallery.album;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mcreatik.gallery.photo.Gallery;

public final class AlbumDtos {

    private AlbumDtos() {
    }

    public record Category(UUID id, String name, int position, long photos, Gallery gallery) {
    }

    /** {@code gallery}: GUEST or CLIENT; omitted means the event's default (CLIENT for client-type events, else GUEST). */
    public record CategoryRequest(String name, String gallery) {
    }

    /** {@code categoryId} null moves the photos to "not sorted". */
    public record AssignRequest(List<UUID> photoIds, UUID categoryId) {
    }

    public record Client(UUID id, String name, String email, String phone, boolean mustChangePassword,
                         boolean disabled, Instant createdAt, Instant lastLoginAt) {
    }

    public record CreateClientRequest(String name, String email, String password, String phone) {
    }

    /** {@code phone}: the couple's WhatsApp number, kept so the studio can send the login again. */
    public record UpdateClientRequest(String newPassword, Boolean disabled, String phone) {
    }

    /** {@code album} true: the whole album selection; else the listed photos. */
    public record DownloadRequest(List<UUID> photoIds, Boolean album) {
    }

    public record SelectedPhoto(UUID photoId, String fileName, String thumbnailUrl, Instant selectedAt,
                                String selectedBy, UUID categoryId) {
    }

    /** {@code limit} null: the couple may select any number of photos. */
    public record Album(Integer limit, List<SelectedPhoto> selected) {
    }

    public record AlbumLimitRequest(Integer limit) {
    }
}
