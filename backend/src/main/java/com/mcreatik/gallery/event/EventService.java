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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.mcreatik.gallery.album.AlbumService;
import com.mcreatik.gallery.auth.AdminUser;
import com.mcreatik.gallery.billing.BillingService;
import com.mcreatik.gallery.auth.AdminUserRepository;
import com.mcreatik.gallery.auth.StudioBranding;
import com.mcreatik.gallery.common.ApiException;
import com.mcreatik.gallery.config.GalleryProperties;
import com.mcreatik.gallery.face.FaceSearchService;
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
    private final AdminUserRepository admins;
    private final StudioBranding branding;
    private final AlbumService album;
    private final BillingService billing;
    private final FaceSearchService faces;

    public EventService(EventRepository events, PhotoRepository photos, UploaderRepository uploaders,
                        StorageService storage, PhotoMapper photoMapper, GalleryBroadcaster broadcaster,
                        GalleryProperties properties, AdminUserRepository admins, StudioBranding branding,
                        AlbumService album, BillingService billing, FaceSearchService faces) {
        this.events = events;
        this.photos = photos;
        this.uploaders = uploaders;
        this.storage = storage;
        this.photoMapper = photoMapper;
        this.broadcaster = broadcaster;
        this.properties = properties;
        this.admins = admins;
        this.branding = branding;
        this.album = album;
        this.billing = billing;
        this.faces = faces;
    }

    @Transactional(readOnly = true)
    public List<EventDtos.EventSummary> list(UUID ownerId) {
        // The super admin sees every studio's events; a studio only its own.
        List<Event> owned = isSuperAdmin(ownerId) ? events.findAllByOrderByEventDateDescCreatedAtDesc()
                : events.findByOwnerIdOrderByEventDateDescCreatedAtDesc(ownerId);
        Map<UUID, String> names = ownerNames();
        Map<UUID, Long> counts = new HashMap<>();
        if (!owned.isEmpty()) {
            for (Object[] row : photos.countReadyByEvent(owned.stream().map(Event::getId).toList())) {
                counts.put((UUID) row[0], ((Number) row[1]).longValue());
            }
        }
        return owned.stream().map(e -> new EventDtos.EventSummary(e.getId(), e.getName(), e.getSlug(),
                e.getEventDate(), e.getStatus(), properties.galleryUrl(e.getSlug()),
                counts.getOrDefault(e.getId(), 0L), e.getCreatedAt(), e.getOwnerId(), names.get(e.getOwnerId()),
                e.getEventType())).toList();
    }

    @Transactional
    public EventDtos.EventDetail create(UUID ownerId, EventDtos.CreateEventRequest request) {
        String slug = request.slug() == null || request.slug().isBlank()
                ? uniqueSlug(request.name())
                : validatedSlug(request.slug(), null);
        UUID owner = ownerId;
        if (request.ownerId() != null && !request.ownerId().equals(ownerId)) {
            // Only the super admin creates events for a studio.
            if (!isSuperAdmin(ownerId)) {
                throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "You can only create events for your own studio");
            }
            owner = admins.findById(request.ownerId()).map(AdminUser::getId)
                    .orElseThrow(() -> ApiException.badRequest("INVALID_STUDIO", "Unknown studio"));
        }
        EventType type = request.eventType() == null ? EventType.MAIN : request.eventType();
        // Plans: the pack's allowance (carried over first), then bought events; otherwise 402 and the dashboard offers to pay.
        String charge = billing.reserveEvent(owner, type);
        Event event = new Event(owner, request.name().trim(), slug, request.eventDate(),
                request.status() == null ? EventStatus.UPCOMING : request.status());
        event.setEventType(type);
        event.setSplitGalleries(true);
        event.setStartTime(request.startTime());
        event.setEndTime(request.endTime());
        validateTimes(event);
        BillingService.Settings prices = billing.settings();
        if (type == EventType.CLIENT) {
            // A gallery for the couple only: no guest QR gallery, storage bought for it, active from today.
            event.setLiveGalleryEnabled(false);
            boolean bought = "CLIENT".equals(charge);
            event.setStorageQuotaGb(bought ? billing.takeClientStorage(owner) : 0);
            // The months chosen when the gallery was bought (at least 1).
            event.setActiveUntil(today().plusMonths(bought ? billing.takeClientMonths(owner) : prices.activeMonths()));
        } else {
            event.setStorageQuotaGb(prices.freeStorageGb());
            event.setActiveUntil(request.eventDate().plusMonths(prices.activeMonths()));
        }
        // McreatiK and unlimited studios may choose the date themselves.
        if (request.retentionUntil() != null && billing.freeAccount(ownerId)) {
            event.setActiveUntil(request.retentionUntil());
        }
        event.setRetentionUntil(event.getActiveUntil().plusDays(DELETE_AFTER_DAYS));
        events.save(event);
        events.flush();
        billing.recordCharge(owner, event.getId(), charge);
        album.createDefaultCategories(event.getId(), type == EventType.CLIENT);
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
        // The name and date are printed on the QR cards and shown to guests: only McreatiK can change them later.
        boolean renames = r.name() != null && !r.name().trim().equals(event.getName());
        boolean redates = r.eventDate() != null && !r.eventDate().equals(event.getEventDate());
        if ((renames || redates) && !isSuperAdmin(ownerId)) {
            throw ApiException.conflict("EVENT_LOCKED",
                    "The event name and date can't be changed after the event is created. Please contact McreatiK.");
        }
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
            // An event created long before its date keeps its full active period after the (new) date.
            if (event.getEventType() != EventType.CLIENT && event.getActiveUntil() != null) {
                LocalDate earliest = r.eventDate().plusMonths(billing.settings().activeMonths());
                if (earliest.isAfter(event.getActiveUntil())) {
                    event.setActiveUntil(earliest);
                    event.setRetentionUntil(earliest.plusDays(DELETE_AFTER_DAYS));
                }
            }
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
                    .filter(p -> p.getStatus() == PhotoStatus.READY && !p.isHidden())
                    .orElseThrow(() -> ApiException.badRequest("INVALID_COVER",
                            "Cover must be a ready photo of this event that guests can see"));
            event.setCoverPhotoId(cover.getId());
        }
        if (Boolean.TRUE.equals(r.clearRetention()) || r.retentionUntil() != null) {
            // Paying studios extend with "Keep N more months"; McreatiK and unlimited studios set the date by hand.
            if (!billing.freeAccount(ownerId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "EXTEND_INSTEAD",
                        "Use Keep more months on the event page to keep this gallery open longer");
            }
            if (Boolean.TRUE.equals(r.clearRetention())) {
                event.setActiveUntil(null);
                event.setRetentionUntil(null);
            } else {
                event.setActiveUntil(r.retentionUntil());
                event.setRetentionUntil(r.retentionUntil());
            }
        }
        event.touch();
        if (previousStatus != event.getStatus()) {
            broadcaster.eventStatusChanged(event.getId(), event.getStatus());
        }
        return toDetail(event);
    }

    /** Permanently deletes the event, its uploaders, its photos and every stored file. */
    public void delete(UUID ownerId, UUID eventId) {
        if (!isSuperAdmin(ownerId)) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Events can't be deleted. You can delete its photos, or ask McreatiK to remove the event.");
        }
        remove(owned(ownerId, eventId));
    }

    /** Removes the event and every stored file (expiry, a deleted studio, or McreatiK deleting it). */
    public void remove(Event event) {
        String prefix = StorageKeys.eventPrefix(event.getId());
        storage.deletePrefix(StorageArea.ORIGINALS, prefix);
        storage.deletePrefix(StorageArea.MEDIA, prefix);
        events.deleteById(event.getId());
        faces.removeEvent(event);
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
                            u.getQueueFailed(), u.getLastError(), lastUpload.get(u.getId()), u.isPaused());
                }).toList();
        int active = (int) uploaderStats.stream().filter(u -> u.status() != UploaderStatus.OFFLINE).count();

        var latest = photos.findByEventIdAndStatusOrderByReadyAtDesc(event.getId(), PhotoStatus.READY, Limit.of(1))
                .stream().findFirst().map(photoMapper::toAdmin).orElse(null);

        Object[] flags = photos.countHiddenAndLiked(event.getId()).getFirst();
        return new EventDtos.EventStats(total, ready, inProgress, failed, bytes, active, latest, uploaderStats,
                ((Number) flags[0]).longValue(), ((Number) flags[1]).longValue());
    }

    /** A hidden photo cannot stay the cover guests see. */
    @Transactional
    public void clearCoverIf(UUID eventId, UUID photoId) {
        events.findById(eventId).filter(e -> photoId.equals(e.getCoverPhotoId())).ifPresent(e -> {
            e.setCoverPhotoId(null);
            e.touch();
        });
    }

    /** The caller's event; the super admin can open any studio's event (support). */
    public Event owned(UUID ownerId, UUID eventId) {
        return events.findByIdAndOwnerId(eventId, ownerId)
                .or(() -> isSuperAdmin(ownerId) ? events.findById(eventId) : java.util.Optional.empty())
                .orElseThrow(() -> ApiException.notFound("Event"));
    }

    private boolean isSuperAdmin(UUID adminId) {
        return admins.findById(adminId).map(AdminUser::isSuperAdmin).orElse(false);
    }

    private Map<UUID, String> ownerNames() {
        Map<UUID, String> names = new HashMap<>();
        admins.findAll().forEach(a -> names.put(a.getId(), a.getDisplayName()));
        return names;
    }

    // ---- Public ----

    @Transactional(readOnly = true)
    public Event publicEvent(String slug) {
        Event event = events.findBySlug(slug)
                .filter(e -> e.getStatus().isPubliclyVisible())
                .orElseThrow(() -> ApiException.notFound("Event"));
        if (!event.isLiveGalleryEnabled()) {
            throw galleryUnavailable(event);
        }
        if (event.isExpired(today())) {
            throw galleryExpired(event);
        }
        return event;
    }

    /** Guests and the couple see this once the gallery's active period has ended. */
    public ApiException galleryExpired(Event e) {
        return new ApiException(HttpStatus.FORBIDDEN, "GALLERY_EXPIRED",
                "This gallery has expired. Please contact " + branding.ofOwner(e.getOwnerId()).name() + ".");
    }

    static LocalDate today() {
        return LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
    }

    /** Files are kept this long after a gallery's active period ends, so the studio can still extend it. */
    public static final int DELETE_AFTER_DAYS = 30;

    @Transactional(readOnly = true)
    public EventDtos.PublicEvent toPublic(Event event) {
        long count = photos.countVisible(event.getId());
        return new EventDtos.PublicEvent(event.getName(), event.getSlug(), event.getEventDate(), event.getStatus(),
                event.getStatus() == EventStatus.LIVE, coverUrl(event), count,
                branding.ofOwner(event.getOwnerId()), faces.searchable(event));
    }

    // ---- helpers ----

    private EventDtos.EventDetail toDetail(Event e) {
        return new EventDtos.EventDetail(e.getId(), e.getName(), e.getSlug(), e.getEventDate(), e.getStartTime(),
                e.getEndTime(), e.getStatus(), e.getCoverPhotoId(), coverUrl(e), properties.galleryUrl(e.getSlug()),
                e.getRetentionUntil(), e.getCreatedAt(), e.getUpdatedAt(), e.getOwnerId(),
                admins.findById(e.getOwnerId()).map(AdminUser::getDisplayName).orElse(null),
                branding.ofOwner(e.getOwnerId()), e.isLiveGalleryEnabled(), e.isClientGalleryEnabled(), e.getEventType(),
                e.getActiveUntil(), e.getStorageQuotaGb(), billing.eventBytes(e.getId()), e.isStorageLocked(), e.isExpired(today()));
    }

    /** Guests and the couple see this when McreatiK has turned their gallery off. */
    public ApiException galleryUnavailable(Event e) {
        return new ApiException(HttpStatus.FORBIDDEN, "GALLERY_UNAVAILABLE",
                "This gallery is currently unavailable. Please contact " + branding.ofOwner(e.getOwnerId()).name() + ".");
    }

    /** McreatiK only: turn an event's guest gallery or client gallery on or off. Uploads keep working either way. */
    @Transactional
    public EventDtos.EventDetail setGalleryAccess(UUID adminId, UUID eventId, EventDtos.GalleryAccessRequest r) {
        if (!isSuperAdmin(adminId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only McreatiK can turn galleries on or off");
        }
        Event event = events.findById(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (r.liveGallery() != null) {
            boolean closing = event.isLiveGalleryEnabled() && !r.liveGallery();
            event.setLiveGalleryEnabled(r.liveGallery());
            if (closing) {
                // Tell open galleries only once the change is saved, so a reload sees it too.
                String message = galleryUnavailable(event).getMessage();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        broadcaster.galleryClosed(eventId, message);
                    }
                });
            }
        }
        if (r.clientGallery() != null) {
            event.setClientGalleryEnabled(r.clientGallery());
        }
        return toDetail(event);
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

    /** Used by the retention job: events past their retention date whose photos are still there. */
    public List<Event> expiredEvents(LocalDate today) {
        return events.findExpiredWithPhotos(today);
    }

    /**
     * The retention date has passed: the photos and every stored file are deleted and the gallery is closed
     * (ARCHIVED), but the event itself stays as a business record (with its payments and charges).
     */
    @Transactional
    public void deleteExpired(Event event) {
        String prefix = StorageKeys.eventPrefix(event.getId());
        storage.deletePrefix(StorageArea.ORIGINALS, prefix);
        storage.deletePrefix(StorageArea.MEDIA, prefix);
        events.archive(event.getId());
        events.deletePhotos(event.getId());
        faces.removeEvent(event);
        broadcaster.eventStatusChanged(event.getId(), EventStatus.ARCHIVED);
        log.info("Photos of event {} ({}) deleted; the event record is kept", event.getId(), event.getSlug());
    }
}
