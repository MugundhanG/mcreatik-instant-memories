package com.mcreatik.gallery.album;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AlbumDtos {

    private AlbumDtos() {
    }

    public record Category(UUID id, String name, int position, long photos) {
    }

    public record CategoryRequest(String name) {
    }

    /** {@code categoryId} null moves the photos to "not sorted". */
    public record AssignRequest(List<UUID> photoIds, UUID categoryId) {
    }

    public record Client(UUID id, String name, String email, boolean mustChangePassword, boolean disabled,
                         Instant createdAt, Instant lastLoginAt) {
    }

    public record CreateClientRequest(String name, String email, String password) {
    }

    public record UpdateClientRequest(String newPassword, Boolean disabled) {
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
