package com.mcreatik.gallery.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "events")
public class Event {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "start_time")
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventStatus status;

    @Column(name = "cover_photo_id")
    private UUID coverPhotoId;

    /** Main or Quick (decides what it costs); Client: a couple's gallery without a live guest gallery. */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType = EventType.MAIN;

    @Column(name = "retention_until")
    private LocalDate retentionUntil;

    /** The guests' and the couple's galleries are open until this day (null: no end); files go 30 days later. */
    @Column(name = "active_until")
    private LocalDate activeUntil;

    /** GB of storage (web versions + originals) this event may use. */
    @Column(name = "storage_quota_gb", nullable = false)
    private int storageQuotaGb = 5;

    /** The couple's gallery was turned off because a live event's storage went unpaid. */
    @Column(name = "storage_locked", nullable = false)
    private boolean storageLocked;

    /**
     * Guest and Client galleries are separate: the couple sees only the client gallery. False on events that
     * existed before the split (the couple sees every photo, as before).
     */
    @Column(name = "split_galleries", nullable = false)
    private boolean splitGalleries;

    /** Guests' QR gallery; only McreatiK (super admin) turns it off. */
    @Column(name = "live_gallery_enabled", nullable = false)
    private boolean liveGalleryEnabled = true;

    /** The couple's login; only McreatiK (super admin) turns it off. */
    @Column(name = "client_gallery_enabled", nullable = false)
    private boolean clientGalleryEnabled = true;

    /** "Find my photos" (face search) is on for this event: bought as an add-on, or switched on by McreatiK. */
    @Column(name = "face_search_enabled", nullable = false)
    private boolean faceSearchEnabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Event() {
    }

    public Event(UUID ownerId, String name, String slug, LocalDate eventDate, EventStatus status) {
        this.id = UUID.randomUUID();
        this.ownerId = ownerId;
        this.name = name;
        this.slug = slug;
        this.eventDate = eventDate;
        this.status = status;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }

    public void setEventDate(LocalDate eventDate) {
        this.eventDate = eventDate;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public boolean isSplitGalleries() {
        return splitGalleries;
    }

    public void setSplitGalleries(boolean splitGalleries) {
        this.splitGalleries = splitGalleries;
    }

    public EventType getEventType() {
        return eventType;
    }

    public void setEventType(EventType eventType) {
        this.eventType = eventType;
    }

    public EventStatus getStatus() {
        return status;
    }

    public void setStatus(EventStatus status) {
        this.status = status;
    }

    public UUID getCoverPhotoId() {
        return coverPhotoId;
    }

    public void setCoverPhotoId(UUID coverPhotoId) {
        this.coverPhotoId = coverPhotoId;
    }

    public LocalDate getRetentionUntil() {
        return retentionUntil;
    }

    public void setRetentionUntil(LocalDate retentionUntil) {
        this.retentionUntil = retentionUntil;
    }

    public LocalDate getActiveUntil() {
        return activeUntil;
    }

    public void setActiveUntil(LocalDate activeUntil) {
        this.activeUntil = activeUntil;
    }

    public int getStorageQuotaGb() {
        return storageQuotaGb;
    }

    public void setStorageQuotaGb(int storageQuotaGb) {
        this.storageQuotaGb = storageQuotaGb;
    }

    public boolean isStorageLocked() {
        return storageLocked;
    }

    /** Past its active period (in India): guests and the couple see "expired", the studio can still extend it. */
    public boolean isExpired(LocalDate today) {
        return activeUntil != null && today.isAfter(activeUntil);
    }

    public boolean isLiveGalleryEnabled() {
        return liveGalleryEnabled;
    }

    public void setLiveGalleryEnabled(boolean liveGalleryEnabled) {
        this.liveGalleryEnabled = liveGalleryEnabled;
    }

    public boolean isClientGalleryEnabled() {
        return clientGalleryEnabled;
    }

    public void setClientGalleryEnabled(boolean clientGalleryEnabled) {
        this.clientGalleryEnabled = clientGalleryEnabled;
    }

    public boolean isFaceSearchEnabled() {
        return faceSearchEnabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
