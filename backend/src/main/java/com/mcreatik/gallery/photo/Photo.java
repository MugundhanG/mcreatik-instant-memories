package com.mcreatik.gallery.photo;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "photos")
public class Photo {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "uploader_id")
    private UUID uploaderId;

    @Column(name = "original_file_name", nullable = false)
    private String originalFileName;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "optimized_storage_key")
    private String optimizedStorageKey;

    @Column(name = "thumbnail_storage_key")
    private String thumbnailStorageKey;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    private Integer width;

    private Integer height;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(name = "checksum_sha256", nullable = false)
    private String checksumSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PhotoStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "processing_attempts", nullable = false)
    private int processingAttempts;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Photo() {
    }

    public Photo(UUID id, UUID eventId, UUID uploaderId, String originalFileName, String storageKey,
                 long fileSize, String mimeType, String checksumSha256) {
        this.id = id;
        this.eventId = eventId;
        this.uploaderId = uploaderId;
        this.originalFileName = originalFileName;
        this.storageKey = storageKey;
        this.fileSize = fileSize;
        this.mimeType = mimeType;
        this.checksumSha256 = checksumSha256;
        this.status = PhotoStatus.UPLOADING;
        this.createdAt = now();
        this.updatedAt = this.createdAt;
    }

    /** Microsecond precision so timestamps round-trip through PostgreSQL exactly (cursor pagination). */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public void markUploaded() {
        this.status = PhotoStatus.UPLOADED;
        this.uploadedAt = now();
        this.failureReason = null;
        this.updatedAt = this.uploadedAt;
    }

    /** A re-sent file after a failure: allow the uploader to try again with the same photo id. */
    public void restartUpload(UUID uploaderId, String originalFileName, long fileSize) {
        this.status = PhotoStatus.UPLOADING;
        this.uploaderId = uploaderId;
        this.originalFileName = originalFileName;
        this.fileSize = fileSize;
        this.failureReason = null;
        this.processingAttempts = 0;
        this.updatedAt = now();
    }

    public void markReady(int width, int height, Instant capturedAt, String optimizedKey, String thumbnailKey,
                          String detectedMimeType) {
        Instant now = now();
        this.width = width;
        this.height = height;
        this.capturedAt = capturedAt;
        this.optimizedStorageKey = optimizedKey;
        this.thumbnailStorageKey = thumbnailKey;
        this.mimeType = detectedMimeType;
        this.status = PhotoStatus.READY;
        this.failureReason = null;
        this.processedAt = now;
        this.readyAt = now;
        this.updatedAt = now;
    }

    public void markFailed(String reason) {
        this.status = PhotoStatus.FAILED;
        this.failureReason = reason == null ? "Unknown error"
                : reason.length() > 500 ? reason.substring(0, 500) : reason;
        this.processedAt = now();
        this.updatedAt = this.processedAt;
    }

    public void returnToQueue(String reason) {
        this.status = PhotoStatus.UPLOADED;
        this.failureReason = reason;
        this.updatedAt = now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getUploaderId() {
        return uploaderId;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getOptimizedStorageKey() {
        return optimizedStorageKey;
    }

    public String getThumbnailStorageKey() {
        return thumbnailStorageKey;
    }

    public long getFileSize() {
        return fileSize;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
        return height;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public PhotoStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getProcessingAttempts() {
        return processingAttempts;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public Instant getReadyAt() {
        return readyAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
