package com.mcreatik.gallery.uploader;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "uploaders")
public class Uploader {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private String name;

    @Column(name = "device_identifier")
    private String deviceIdentifier;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "token_prefix", nullable = false)
    private String tokenPrefix;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UploaderStatus status;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "queue_pending", nullable = false)
    private int queuePending;

    @Column(name = "queue_failed", nullable = false)
    private int queueFailed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Uploader() {
    }

    public Uploader(UUID eventId, String name, String tokenHash, String tokenPrefix) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.name = name;
        this.tokenHash = tokenHash;
        this.tokenPrefix = tokenPrefix;
        this.status = UploaderStatus.OFFLINE;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Status shown to admins: a stale heartbeat means OFFLINE regardless of the last reported state. */
    public UploaderStatus effectiveStatus(Instant now, Duration onlineThreshold) {
        if (lastSeenAt == null || lastSeenAt.isBefore(now.minus(onlineThreshold))) {
            return UploaderStatus.OFFLINE;
        }
        return status == UploaderStatus.OFFLINE ? UploaderStatus.ONLINE : status;
    }

    public void recordHeartbeat(String deviceIdentifier, int queuePending, int queueFailed, String lastError) {
        Instant now = Instant.now();
        if (deviceIdentifier != null && !deviceIdentifier.isBlank()) {
            this.deviceIdentifier = deviceIdentifier;
        }
        this.queuePending = Math.max(0, queuePending);
        this.queueFailed = Math.max(0, queueFailed);
        this.lastError = lastError == null || lastError.isBlank() ? null : truncate(lastError, 500);
        this.status = this.lastError == null ? UploaderStatus.ONLINE : UploaderStatus.ERROR;
        this.lastSeenAt = now;
        this.updatedAt = now;
    }

    public void rotateToken(String tokenHash, String tokenPrefix) {
        this.tokenHash = tokenHash;
        this.tokenPrefix = tokenPrefix;
        this.deviceIdentifier = null;
        this.updatedAt = Instant.now();
    }

    public void rename(String name) {
        this.name = name;
        this.updatedAt = Instant.now();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getName() {
        return name;
    }

    public String getDeviceIdentifier() {
        return deviceIdentifier;
    }

    public String getTokenPrefix() {
        return tokenPrefix;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public String getLastError() {
        return lastError;
    }

    public int getQueuePending() {
        return queuePending;
    }

    public int getQueueFailed() {
        return queueFailed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
