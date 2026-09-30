package com.mcreatik.gallery.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mcreatik.gallery.common.ApiException;
import com.mcreatik.gallery.config.GalleryProperties;
import com.mcreatik.gallery.photo.Photo;
import com.mcreatik.gallery.photo.PhotoMapper;
import com.mcreatik.gallery.photo.PhotoRepository;
import com.mcreatik.gallery.photo.PhotoStatus;
import com.mcreatik.gallery.realtime.GalleryBroadcaster;
import com.mcreatik.gallery.storage.StorageArea;
import com.mcreatik.gallery.storage.StorageKeys;
import com.mcreatik.gallery.storage.StorageService;
import com.mcreatik.gallery.uploader.UploaderRepository;
import com.mcreatik.gallery.uploader.UploaderStatus;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository events;
    private final PhotoRepository photos;
    private final UploaderRepository uploaders;
    private final StorageService storage;
    private final PhotoMapper photoMapper;
    private final GalleryBroadcaster broadcaster;
    private final GalleryProperties properties;

    public EventService(EventRepository events, PhotoRepository photos, UploaderRepository uploaders,
                        StorageService storage, PhotoMapper photoMapper, GalleryBroadcaster broadcaster,
                        GalleryProperties properties) {
        this.events = events;
        this.photos = photos;
        this.uploaders = uploaders;
        this.storage = storage;
        this.photoMapper = photoMapper;
        this.broadcaster = broadcaster;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<EventDtos.EventSummary> list(UUID ownerId) {
        List<Event> owned = events.findByOwnerIdOrderByEventDateDescCreatedAtDesc(ownerId);
        Map<UUID, Long> counts = new HashMap<>();
        if (!owned.isEmpty()) {
            for (Object[] row : photos.countReadyByEvent(owned.stream().map(Event::getId).toList())) {
                counts.put((UUID) row[0], ((Number) row[1]).longValue());
            }
        }
        return owned.stream().map(e -> new EventDtos.EventSummary(e.getId(), e.getName(), e.getSlug(),
                e.getEventDate(), e.getStatus(), properties.galleryUrl(e.getSlug()),
                counts.getOrDefault(e.getId(), 0L), e.getCreatedAt())).toList();
    }

    @Transactional
    public EventDtos.EventDetail create(UUID ownerId, EventDtos.CreateEventRequest request) {
        String slug = request.slug() == null || request.slug().isBlank()
                ? uniqueSlug(request.name())
                : validatedSlug(request.slug(), null);
        Event event = new Event(ownerId, request.name().trim(), slug, request.eventDate(),
                request.status() == null ? EventStatus.UPCOMING : request.status());
        event.setStartTime(request.startTime());
        event.setEndTime(request.endTime());
        validateTimes(event);
        event.setRetentionUntil(request.retentionUntil() != null ? request.retentionUntil()
                : request.eventDate().plusDays(properties.retention().defaultDays()));
        events.save(event);
        return toDetail(event);
    }

    @Transactional(readOnly = true)
    public EventDtos.EventDetail get(UUID ownerId, UUID eventId) {
        return toDetail(owned(ownerId, eventId));
    }

    @Transactional
    public EventDtos.EventDetail update(UUID ownerId, UUID eventId, EventDtos.UpdateEventRequest r) {
        Event event = owned(ownerId, eventId);
        EventStatus previousStatus = event.getStatus();
        if (r.name() != null) {
            if (r.name().isBlank()) {
                throw ApiException.badRequest("INVALID_NAME", "Name cannot be empty");
            }
            event.setName(r.name().trim());
        }
        if (r.slug() != null && !r.slug().equals(event.getSlug())) {
            event.setSlug(validatedSlug(r.slug(), event.getId()));
        }
        if (r.eventDate() != null) {
            event.setEventDate(r.eventDate());
        }
        if (r.startTime() != null) {
            event.setStartTime(r.startTime());
        }
        if (r.endTime() != null) {
            event.setEndTime(r.endTime());
        }
        validateTimes(event);
        if (r.status() != null) {
            event.setStatus(r.status());
        }
        if (r.coverPhotoId() != null) {
            Photo cover = photos.findByIdAndEventId(r.coverPhotoId(), event.getId())
                    .filter(p -> p.getStatus() == PhotoStatus.READY)
                    .orElseThrow(() -> ApiException.badRequest("INVALID_COVER", "Cover must be a ready photo of this event"));
            event.setCoverPhotoId(cover.getId());
        }
        if (Boolean.TRUE.equals(r.clearRetention())) {
            event.setRetentionUntil(null);
        } else if (r.retentionUntil() != null) {
            event.setRetentionUntil(r.retentionUntil());
        }
        event.touch();
        if (previousStatus != event.getStatus()) {
            broadcaster.eventStatusChanged(event.getId(), event.getStatus());
        }
        return toDetail(event);
    }

    /** Permanently deletes the event, its uploaders, its photos and every stored file. */
    public void delete(UUID ownerId, UUID eventId) {
        Event event = owned(ownerId, eventId);
        String prefix = StorageKeys.eventPrefix(event.getId());
        storage.deletePrefix(StorageArea.ORIGINALS, prefix);
        storage.deletePrefix(StorageArea.MEDIA, prefix);
        events.deleteById(event.getId());
        broadcaster.eventStatusChanged(event.getId(), EventStatus.ARCHIVED);
        log.info("Deleted event {} ({})", event.getId(), event.getSlug());
    }

    @Transactional(readOnly = true)
    public EventDtos.EventStats stats(UUID ownerId, UUID eventId) {
        Event event = owned(ownerId, eventId);
        long total = 0;
        long ready = 0;
        long failed = 0;
        long inProgress = 0;
        long bytes = 0;
        for (Object[] row : photos.countByStatus(event.getId())) {
            PhotoStatus status = PhotoStatus.valueOf((String) row[0]);
            long count = ((Number) row[1]).longValue();
            total += count;
            bytes += ((Number) row[2]).longValue();
            switch (status) {
                case READY -> ready += count;
                case FAILED -> failed += count;
                default -> inProgress += count;
            }
        }

        Map<UUID, long[]> perUploader = new HashMap<>();
        Map<UUID, Instant> lastUpload = new HashMap<>();
        for (Object[] row : photos.countByUploader(event.getId())) {
            if (row[0] == null) {
                continue; // photos of deleted uploaders
            }
            UUID id = (UUID) row[0];
            perUploader.put(id, new long[] {((Number) row[2]).longValue(), ((Number) row[1]).longValue()});
            lastUpload.put(id, toInstant(row[3]));
        }

        Instant now = Instant.now();
        List<EventDtos.UploaderStats> uploaderStats = uploaders.findByEventIdOrderByCreatedAtAsc(event.getId()).stream()
                .map(u -> {
                    long[] counts = perUploader.getOrDefault(u.getId(), new long[] {0, 0});
                    return new EventDtos.UploaderStats(u.getId(), u.getName(),
                            u.effectiveStatus(now, properties.uploader().onlineThreshold()), u.getLastSeenAt(),
                            u.getDeviceIdentifier(), u.getTokenPrefix(), counts[0], counts[1], u.getQueuePending(),
                            u.getQueueFailed(), u.getLastError(), lastUpload.get(u.getId()));
                }).toList();
        int active = (int) uploaderStats.stream().filter(u -> u.status() != UploaderStatus.OFFLINE).count();

        var latest = photos.findByEventIdAndStatusOrderByReadyAtDesc(event.getId(), PhotoStatus.READY, Limit.of(1))
                .stream().findFirst().map(photoMapper::toAdmin).orElse(null);

        return new EventDtos.EventStats(total, ready, inProgress, failed, bytes, active, latest, uploaderStats);
    }

    public Event owned(UUID ownerId, UUID eventId) {
        return events.findByIdAndOwnerId(eventId, ownerId).orElseThrow(() -> ApiException.notFound("Event"));
    }

    // ---- Public ----

    @Transactional(readOnly = true)
    public Event publicEvent(String slug) {
        return events.findBySlug(slug)
                .filter(e -> e.getStatus().isPubliclyVisible())
                .orElseThrow(() -> ApiException.notFound("Event"));
    }

    @Transactional(readOnly = true)
    public EventDtos.PublicEvent toPublic(Event event) {
        long count = photos.countReadyByEvent(List.of(event.getId())).stream()
                .findFirst().map(r -> ((Number) r[1]).longValue()).orElse(0L);
        return new EventDtos.PublicEvent(event.getName(), event.getSlug(), event.getEventDate(), event.getStatus(),
                event.getStatus() == EventStatus.LIVE, coverUrl(event), count);
    }

    // ---- helpers ----

    private EventDtos.EventDetail toDetail(Event e) {
        return new EventDtos.EventDetail(e.getId(), e.getName(), e.getSlug(), e.getEventDate(), e.getStartTime(),
                e.getEndTime(), e.getStatus(), e.getCoverPhotoId(), coverUrl(e), properties.galleryUrl(e.getSlug()),
                e.getRetentionUntil(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private String coverUrl(Event e) {
        if (e.getCoverPhotoId() == null) {
            return null;
        }
        return photos.findById(e.getCoverPhotoId()).map(Photo::getOptimizedStorageKey).map(photoMapper::url).orElse(null);
    }

    private String uniqueSlug(String name) {
        for (int i = 0; i < 10; i++) {
            String candidate = Slugs.generate(name);
            if (!events.existsBySlug(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique slug");
    }

    private String validatedSlug(String slug, UUID currentEventId) {
        String clean = slug.trim().toLowerCase();
        if (!Slugs.isValid(clean)) {
            throw ApiException.badRequest("INVALID_SLUG",
                    "Slug must be 3-80 characters of lowercase letters, numbers and single hyphens");
        }
        events.findBySlug(clean).filter(e -> !e.getId().equals(currentEventId)).ifPresent(e -> {
            throw ApiException.conflict("SLUG_TAKEN", "This gallery address is already in use");
        });
        return clean;
    }

    private static void validateTimes(Event e) {
        if (e.getStartTime() != null && e.getEndTime() != null && e.getEndTime().isBefore(e.getStartTime())) {
            throw ApiException.badRequest("INVALID_TIMES", "End time must be after start time");
        }
    }

    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant i) {
            return i;
        }
        if (value instanceof java.time.OffsetDateTime o) {
            return o.toInstant();
        }
        if (value instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        throw new IllegalArgumentException("Unexpected timestamp type " + value.getClass());
    }

    /** Used by the retention job. */
    public List<Event> expiredEvents(LocalDate today) {
        return events.findByRetentionUntilBefore(today);
    }

    public void deleteExpired(Event event) {
        delete(event.getOwnerId(), event.getId());
    }
}
