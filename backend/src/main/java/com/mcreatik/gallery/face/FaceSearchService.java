package com.mcreatik.gallery.face;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.mcreatik.gallery.common.ApiException;
import com.mcreatik.gallery.config.GalleryProperties;
import com.mcreatik.gallery.event.Event;
import com.mcreatik.gallery.event.EventType;
import com.mcreatik.gallery.photo.Photo;
import com.mcreatik.gallery.photo.PhotoDtos;
import com.mcreatik.gallery.photo.PhotoMapper;
import com.mcreatik.gallery.photo.PhotoRepository;

/**
 * "Find my photos". The selfie is only ever held in memory for the duration of one request: it is handed to the face
 * engine and dropped, never stored and never logged. Face ids (not faces) are stored per photo; deleting a photo,
 * an event or the add-on removes them here (cascade) and in the engine (explicit calls).
 */
@Service
public class FaceSearchService {

    private static final Logger log = LoggerFactory.getLogger(FaceSearchService.class);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final long HOUR_MS = 3_600_000L;
    private static final Runnable NOTHING = () -> {
    };

    public record FindResult(List<PhotoDtos.PublicPhoto> photos, int count) {
    }

    /** {@code indexed} of {@code total} guest photos have their faces in the engine. */
    public record Status(boolean enabled, boolean available, long indexed, long total) {
    }

    private final FaceSearchProvider provider;
    private final JdbcTemplate jdbc;
    private final PhotoRepository photos;
    private final PhotoMapper mapper;
    private final GalleryProperties.FaceSearch config;

    private final Set<String> knownCollections = ConcurrentHashMap.newKeySet();
    private final Map<String, ArrayDeque<Long>> searchesByIp = new ConcurrentHashMap<>();

    public FaceSearchService(FaceSearchProvider provider, JdbcTemplate jdbc, PhotoRepository photos, PhotoMapper mapper,
                             GalleryProperties properties) {
        this.provider = provider;
        this.jdbc = jdbc;
        this.photos = photos;
        this.mapper = mapper;
        this.config = properties.faceSearch();
    }

    /** The global switch (FACE_SEARCH_ENABLED). */
    public boolean globallyEnabled() {
        return config.enabled();
    }

    public String collectionName(UUID eventId) {
        return config.collectionPrefix() + "-event-" + eventId;
    }

    /** Guests can search this event: the switch is on, the event has the add-on (the gallery being open is checked by the caller). */
    public boolean searchable(Event event) {
        return config.enabled() && event.isFaceSearchEnabled() && event.getEventType() != EventType.CLIENT;
    }

    public void requireSearchable(Event event) {
        if (!searchable(event)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FACE_SEARCH_OFF", "Face search is not available for this gallery");
        }
    }

    /** Creates the event's collection once per run (the engine treats an existing one as fine). */
    void ensureCollection(UUID eventId) {
        String name = collectionName(eventId);
        if (!knownCollections.contains(name)) {
            provider.createCollection(name);
            knownCollections.add(name);
        }
    }

    // ---- Switching on and off ----

    /** The add-on is on for this event; the worker creates the collection and indexes the photos that are there. */
    @Transactional
    public void enable(UUID eventId) {
        jdbc.update("UPDATE events SET face_search_enabled = true, updated_at = now() WHERE id = ?", eventId);
    }

    /** The add-on is off (or a payment was rejected): the event's faces and collection are deleted. */
    @Transactional
    public void disable(UUID eventId) {
        jdbc.update("UPDATE events SET face_search_enabled = false, updated_at = now() WHERE id = ?", eventId);
        jdbc.update("DELETE FROM photo_faces WHERE event_id = ?", eventId);
        jdbc.update("UPDATE photos SET face_indexed_at = NULL, face_attempts = 0, face_next_attempt_at = NULL WHERE event_id = ?", eventId);
        dropCollectionAfterCommit(eventId);
    }

    /** The event is deleted or its photos are (retention): its collection goes once the change is committed. */
    public void removeEvent(Event event) {
        if (event.isFaceSearchEnabled()) {
            dropCollectionAfterCommit(event.getId());
        }
    }

    private void dropCollectionAfterCommit(UUID eventId) {
        String name = collectionName(eventId);
        knownCollections.remove(name);
        afterCommit(() -> {
            try {
                provider.deleteCollection(name);
            } catch (Exception e) {
                log.warn("Could not delete face collection {} (the nightly check retries): {}", name, e.toString());
            }
        });
    }

    /**
     * Before photos are deleted: remembers their face ids. Run the returned action once the delete is committed to
     * remove the faces from the engine too (the rows themselves go by cascade).
     */
    public Runnable captureFaces(UUID eventId, Collection<UUID> photoIds) {
        if (photoIds.isEmpty()) {
            return NOTHING;
        }
        String marks = String.join(",", java.util.Collections.nCopies(photoIds.size(), "?"));
        Object[] args = new Object[photoIds.size() + 1];
        args[0] = eventId;
        int i = 1;
        for (UUID id : photoIds) {
            args[i++] = id;
        }
        List<String> faceIds = jdbc.queryForList(
                "SELECT face_id FROM photo_faces WHERE event_id = ? AND photo_id IN (" + marks + ")", String.class, args);
        if (faceIds.isEmpty()) {
            return NOTHING;
        }
        String name = collectionName(eventId);
        return () -> {
            try {
                provider.deleteFaces(name, faceIds);
            } catch (Exception e) {
                log.warn("Could not delete {} faces from {}: {}", faceIds.size(), name, e.toString());
            }
        };
    }

    /** Runs after the surrounding transaction commits (at once when there is none). */
    static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    // ---- Dashboard ----

    @Transactional(readOnly = true)
    public Status status(Event event) {
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM photos WHERE event_id = ? AND gallery = 'GUEST' AND status = 'READY'", Long.class, event.getId());
        Long indexed = jdbc.queryForObject("""
                SELECT count(*) FROM photos WHERE event_id = ? AND gallery = 'GUEST' AND status = 'READY'
                  AND face_indexed_at IS NOT NULL""", Long.class, event.getId());
        return new Status(event.isFaceSearchEnabled(), config.enabled(), indexed == null ? 0 : indexed, total == null ? 0 : total);
    }

    // ---- Guest search ----

    /**
     * Finds the guest photos the selfie appears in. {@code ip} and the event's daily cap bound what a stranger can
     * spend on AWS. Throws NO_FACE when the selfie has no face, BAD_IMAGE when the engine cannot read it.
     */
    public FindResult find(Event event, byte[] selfie, String ip) {
        requireSearchable(event);
        if (!takeIpSlot(ip)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "FACE_SEARCH_LIMIT",
                    "You have searched a lot in the last hour. Please try again later, or scroll through all the photos.");
        }
        if (!takeEventSlot(event.getId())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "FACE_SEARCH_LIMIT",
                    "Face search has reached its limit for today on this gallery. Please try again tomorrow.");
        }
        List<String> faceIds;
        try {
            faceIds = provider.searchFaces(collectionName(event.getId()), selfie, config.matchThreshold(), config.maxMatches());
        } catch (FaceSearchProvider.NoFaceException e) {
            throw ApiException.badRequest("NO_FACE", "We couldn't find a face in that photo. Try a clear, well-lit selfie.");
        } catch (FaceSearchProvider.BadImageException e) {
            throw ApiException.badRequest("BAD_IMAGE", "We couldn't read that photo. Please try another one.");
        }
        List<Photo> found = faceIds.isEmpty() ? List.of() : photos.findVisibleByFaceIds(event.getId(), faceIds);
        return new FindResult(found.stream().map(mapper::toPublic).toList(), found.size());
    }

    private boolean takeIpSlot(String ip) {
        long now = System.currentTimeMillis();
        ArrayDeque<Long> times = searchesByIp.computeIfAbsent(ip == null ? "?" : ip, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > HOUR_MS) {
                times.pollFirst();
            }
            if (times.size() >= config.ipPerHour()) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }

    /** Counts one search for today (India time) unless the event's daily cap is already used. */
    private boolean takeEventSlot(UUID eventId) {
        List<Integer> row = jdbc.queryForList("""
                INSERT INTO face_search_usage (event_id, day, searches) VALUES (?, ?, 1)
                ON CONFLICT (event_id, day) DO UPDATE SET searches = face_search_usage.searches + 1
                    WHERE face_search_usage.searches < ?
                RETURNING searches""", Integer.class, eventId, LocalDate.now(INDIA), config.eventDailyCap());
        return !row.isEmpty();
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    void evictIdleIps() {
        long now = System.currentTimeMillis();
        searchesByIp.values().removeIf(t -> {
            synchronized (t) {
                return t.isEmpty() || now - t.peekLast() > HOUR_MS;
            }
        });
    }

    // ---- Nightly check ----

    /**
     * Deletes collections whose event is gone, archived (photos deleted) or no longer has the add-on: the safety net
     * for a provider call that failed earlier. Returns how many were deleted.
     */
    @Scheduled(cron = "0 47 3 * * *", zone = "UTC")
    public int sweepOrphanCollections() {
        if (!config.enabled()) {
            return 0;
        }
        String prefix = config.collectionPrefix() + "-event-";
        int deleted = 0;
        try {
            for (String name : provider.listCollections(prefix)) {
                UUID eventId = parseEventId(name, prefix);
                if (eventId == null) {
                    continue;
                }
                List<Boolean> wanted = jdbc.queryForList("""
                        SELECT face_search_enabled AND photos_deleted_at IS NULL AND status <> 'ARCHIVED'
                        FROM events WHERE id = ?""", Boolean.class, eventId);
                if (wanted.isEmpty() || !Boolean.TRUE.equals(wanted.getFirst())) {
                    provider.deleteCollection(name);
                    knownCollections.remove(name);
                    deleted++;
                    log.info("Face search: deleted orphan collection {}", name);
                }
            }
        } catch (Exception e) {
            log.warn("Face search: nightly check failed: {}", e.toString());
        }
        return deleted;
    }

    private static UUID parseEventId(String name, String prefix) {
        try {
            return UUID.fromString(name.substring(prefix.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
