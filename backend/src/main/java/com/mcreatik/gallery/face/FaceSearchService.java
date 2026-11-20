package com.mcreatik.gallery.face;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
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
    /** Searches one automatic update makes (the strongest faces of the guest's first match). */
    static final int REFRESH_SEEDS = 3;
    private static final Runnable NOTHING = () -> {
    };

    public record FindResult(List<PhotoDtos.PublicPhoto> photos, int count, String matchId) {
    }

    /** A remembered match: its photos now, and whether photos were indexed since it was last checked. */
    public record MatchResult(List<PhotoDtos.PublicPhoto> photos, int count, boolean hasNew) {
    }

    /** guest_activity types the server writes for the studio's "Find my photos" stats. */
    public static final String TYPE_SEARCH = "FACE_SEARCH";
    public static final String TYPE_FAILED = "FACE_FAILED";
    public static final String TYPE_FOUND = "FACE_FOUND";
    public static final String TYPE_UPDATE = "FACE_UPDATE";
    private static final java.util.regex.Pattern VISITOR_ID = java.util.regex.Pattern.compile("^[A-Za-z0-9-]{8,40}$");

    /** {@code indexed} of {@code total} guest photos have their faces in the engine. */
    public record Status(boolean enabled, boolean available, long indexed, long total) {
    }

    private final FaceSearchProvider provider;
    private final JdbcTemplate jdbc;
    private final PhotoRepository photos;
    private final PhotoMapper mapper;
    private final GuestMatchService matches;
    private final GalleryProperties.FaceSearch config;

    private final Set<String> knownCollections = ConcurrentHashMap.newKeySet();
    private final Map<String, ArrayDeque<Long>> searchesByIp = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<Long>> searchesByVisitor = new ConcurrentHashMap<>();

    public FaceSearchService(FaceSearchProvider provider, JdbcTemplate jdbc, PhotoRepository photos, PhotoMapper mapper,
                             GuestMatchService matches, GalleryProperties properties) {
        this.provider = provider;
        this.jdbc = jdbc;
        this.photos = photos;
        this.mapper = mapper;
        this.matches = matches;
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

    /** Private gallery: guests see only the photos a selfie found them in, never the whole gallery. */
    public boolean isPrivate(Event event) {
        return searchable(event) && event.isFacePrivate();
    }

    /** Turns the private gallery on or off; it needs face search, so turning face search off also ends it. */
    public void setPrivate(UUID eventId, boolean on) {
        jdbc.update("UPDATE events SET face_private = ?, updated_at = now() WHERE id = ?", on, eventId);
    }

    /** The studio bought (sign 1) or a rejected payment took back (sign -1) the add-on for this event. */
    public void setPaid(UUID eventId, boolean paid) {
        jdbc.update("UPDATE events SET face_search_paid = ? WHERE id = ?", paid, eventId);
    }

    /** True when the photo is one of the remembered match's (a private gallery's downloads need this). */
    public boolean matchShows(Event event, String matchId, UUID photoId) {
        UUID id = parse(matchId);
        return id != null && matches.find(event.getId(), id)
                .map(m -> visiblePhotos(event.getId(), m.faceIds()).stream().anyMatch(p -> p.id().equals(photoId)))
                .orElse(false);
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
        jdbc.update("UPDATE events SET face_search_enabled = false, face_private = false, updated_at = now() WHERE id = ?", eventId);
        jdbc.update("DELETE FROM photo_faces WHERE event_id = ?", eventId);
        matches.deleteForEvent(eventId);
        jdbc.update("UPDATE photos SET face_indexed_at = NULL, face_attempts = 0, face_next_attempt_at = NULL WHERE event_id = ?", eventId);
        dropCollectionAfterCommit(eventId);
    }

    /** The event is deleted or its photos are (retention): its collection goes once the change is committed. */
    public void removeEvent(Event event) {
        matches.deleteForEvent(event.getId()); // an archived event keeps its row, so the cascade is not enough
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
     * Finds the guest photos the selfie appears in and remembers the match ({@code matchId}, null when nothing matched).
     * {@code ip} and the event's daily cap bound what a stranger can spend on AWS. Throws NO_FACE when the selfie has no
     * face, BAD_IMAGE when the engine cannot read it. {@code visitorId} (optional) only labels the studio's stats.
     */
    public FindResult find(Event event, byte[] selfie, String ip, String visitorId) {
        requireSearchable(event);
        Slots slots = takeSlots(event, ip, visitorId);
        String visitor = visitor(visitorId);
        record(event.getId(), TYPE_SEARCH, visitor, null);
        Instant checkedAt = matches.now();
        List<FaceSearchProvider.FaceMatch> found;
        try {
            found = provider.searchFaces(collectionName(event.getId()), selfie, config.matchThreshold(), config.maxMatches());
        } catch (FaceSearchProvider.NoFaceException e) {
            slots.refund(); // the engine did no billable search: a bad selfie must not burn the venue's daily cap
            record(event.getId(), TYPE_FAILED, visitor, null);
            throw ApiException.badRequest("NO_FACE", "We couldn't find a face in that photo. Try a clear, well-lit selfie.");
        } catch (FaceSearchProvider.BadImageException e) {
            slots.refund();
            record(event.getId(), TYPE_FAILED, visitor, null);
            throw ApiException.badRequest("BAD_IMAGE", "We couldn't read that photo. Please try another one.");
        } catch (RuntimeException e) {
            slots.refund(); // provider outage: nothing was searched
            throw e;
        }
        List<String> faceIds = found.stream().map(FaceSearchProvider.FaceMatch::faceId).distinct().toList();
        List<String> seeds = found.stream().sorted(Comparator.comparingDouble(FaceSearchProvider.FaceMatch::similarity).reversed())
                .map(FaceSearchProvider.FaceMatch::faceId).distinct().limit(GuestMatchService.MAX_SEEDS).toList();
        List<PhotoDtos.PublicPhoto> photosFound = visiblePhotos(event.getId(), faceIds);
        String matchId = null;
        if (!faceIds.isEmpty()) {
            matchId = matches.create(event.getId(), seeds, faceIds, checkedAt).toString();
            if (!photosFound.isEmpty()) {
                record(event.getId(), TYPE_FOUND, visitor, photosFound.size());
            }
        }
        return new FindResult(photosFound, photosFound.size(), matchId);
    }

    /** What a remembered match shows now: a database lookup, no engine call and no cost. */
    public MatchResult match(Event event, String matchId) {
        requireSearchable(event);
        GuestMatchService.Match m = requireMatch(event, matchId);
        return view(event, m);
    }

    /**
     * "Update my photos". The engine is only asked (a search by each seed face, about 10 paise each) when photos were
     * indexed after the match was last checked; the same per-IP and per-event caps as a search apply then.
     */
    public MatchResult refresh(Event event, String matchId, String ip, String visitorId) {
        requireSearchable(event);
        GuestMatchService.Match m = requireMatch(event, matchId);
        if (matches.hasNew(m.id())) {
            // At most REFRESH_SEEDS searches per update: guests' galleries update by themselves, so this bounds the cost.
            List<String> seeds = existingFaces(event.getId(), m.seedFaceIds()).stream().limit(REFRESH_SEEDS).toList();
            if (seeds.isEmpty()) {
                matches.update(m.id(), m.seedFaceIds(), m.faceIds(), matches.now()); // nothing to search by; just mark checked
            } else {
                Slots slots = takeSlots(event, ip, visitorId);
                Instant checkedAt = matches.now();
                int before = visiblePhotos(event.getId(), m.faceIds()).size();
                Set<String> merged = new LinkedHashSet<>(m.faceIds());
                try {
                    for (String seed : seeds) {
                        merged.addAll(provider.searchFacesByFaceId(collectionName(event.getId()), seed, config.matchThreshold(),
                                config.maxMatches()));
                    }
                } catch (RuntimeException e) {
                    slots.refund();
                    throw e;
                }
                List<String> all = List.copyOf(merged);
                matches.update(m.id(), seeds, all, checkedAt);
                record(event.getId(), TYPE_UPDATE, visitor(visitorId), Math.max(0, visiblePhotos(event.getId(), all).size() - before));
                m = new GuestMatchService.Match(m.id(), m.eventId(), seeds, all, checkedAt);
            }
            m = matches.find(event.getId(), m.id()).orElse(m);
        }
        return view(event, m);
    }

    /** "Forget me": the match is deleted (always fine to repeat). */
    public void forget(Event event, String matchId) {
        UUID id = parse(matchId);
        if (id != null) {
            matches.delete(event.getId(), id);
        }
    }

    private MatchResult view(Event event, GuestMatchService.Match m) {
        List<PhotoDtos.PublicPhoto> photosFound = visiblePhotos(event.getId(), m.faceIds());
        return new MatchResult(photosFound, photosFound.size(), matches.hasNew(m.id()));
    }

    private GuestMatchService.Match requireMatch(Event event, String matchId) {
        UUID id = parse(matchId);
        return (id == null ? java.util.Optional.<GuestMatchService.Match>empty() : matches.find(event.getId(), id))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MATCH_NOT_FOUND", "That match is gone"));
    }

    private static UUID parse(String id) {
        try {
            return id == null ? null : UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<PhotoDtos.PublicPhoto> visiblePhotos(UUID eventId, List<String> faceIds) {
        List<Photo> found = faceIds.isEmpty() ? List.of() : photos.findVisibleByFaceIds(eventId, faceIds);
        return found.stream().map(mapper::toPublic).toList();
    }

    /** The faces of these ids that are still in the event's photos. */
    private List<String> existingFaces(UUID eventId, List<String> faceIds) {
        if (faceIds.isEmpty()) {
            return List.of();
        }
        String marks = String.join(",", java.util.Collections.nCopies(faceIds.size(), "?"));
        List<Object> args = new java.util.ArrayList<>();
        args.add(eventId);
        args.addAll(faceIds);
        List<String> alive = jdbc.queryForList("SELECT DISTINCT face_id FROM photo_faces WHERE event_id = ? AND face_id IN (" + marks + ")",
                String.class, args.toArray());
        return faceIds.stream().filter(alive::contains).toList();
    }

    /** What one search took (person and IP hour slots, the event's day slot); {@link #refund()} gives it back. */
    private final class Slots {
        private final String personKey;
        private final String ipKey;
        private final UUID eventId;
        private final LocalDate day;

        Slots(String personKey, String ipKey, UUID eventId, LocalDate day) {
            this.personKey = personKey;
            this.ipKey = ipKey;
            this.eventId = eventId;
            this.day = day;
        }

        void refund() {
            giveBackHourSlot(searchesByVisitor, personKey);
            giveBackHourSlot(searchesByIp, ipKey);
            try {
                jdbc.update("UPDATE face_search_usage SET searches = searches - 1 WHERE event_id = ? AND day = ? AND searches > 0", eventId, day);
            } catch (Exception e) {
                log.warn("Could not give back a face search slot: {}", e.toString());
            }
        }
    }

    /**
     * The per-person limit is keyed on the browser's visitor id (a whole venue shares one IP); the IP only has a high
     * ceiling against scripts. Slots are taken before the engine call and given back when it did not run or failed.
     */
    private Slots takeSlots(Event event, String ip, String visitorId) {
        String ipKey = ip == null ? "?" : ip;
        String personKey = VISITOR_ID.matcher(visitorId == null ? "" : visitorId).matches() ? "v:" + visitorId : "ip:" + ipKey;
        if (!takeHourSlot(searchesByVisitor, personKey, config.visitorPerHour())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "FACE_SEARCH_LIMIT",
                    "You have searched a lot in the last hour. Please try again later, or scroll through all the photos.");
        }
        if (!takeHourSlot(searchesByIp, ipKey, config.ipPerHour())) {
            giveBackHourSlot(searchesByVisitor, personKey);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "FACE_SEARCH_LIMIT",
                    "Face search is very busy on this network right now. Please try again in a little while, or scroll through all the photos.");
        }
        LocalDate day = LocalDate.now(INDIA);
        if (!takeEventSlot(event.getId(), day)) {
            giveBackHourSlot(searchesByVisitor, personKey);
            giveBackHourSlot(searchesByIp, ipKey);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "FACE_SEARCH_LIMIT",
                    "Face search has reached its limit for today on this gallery. Please try again tomorrow.");
        }
        return new Slots(personKey, ipKey, event.getId(), day);
    }

    // ---- Studio stats (rows in guest_activity, written here, never by the guest's browser) ----

    private static String visitor(String visitorId) {
        return visitorId != null && VISITOR_ID.matcher(visitorId).matches() ? visitorId : "face-anonymous";
    }

    private void record(UUID eventId, String type, String visitor, Integer amount) {
        try {
            jdbc.update("INSERT INTO guest_activity (event_id, type, visitor_id, amount) VALUES (?, ?, ?, ?)", eventId, type, visitor, amount);
        } catch (Exception e) {
            log.warn("Could not record face search activity: {}", e.toString()); // stats never break a search
        }
    }

    private boolean takeHourSlot(Map<String, ArrayDeque<Long>> byKey, String key, int limit) {
        long now = System.currentTimeMillis();
        ArrayDeque<Long> times = byKey.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > HOUR_MS) {
                times.pollFirst();
            }
            if (times.size() >= limit) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }

    private void giveBackHourSlot(Map<String, ArrayDeque<Long>> byKey, String key) {
        ArrayDeque<Long> times = byKey.get(key);
        if (times != null) {
            synchronized (times) {
                times.pollLast();
            }
        }
    }

    /** Counts one search for today (India time) unless the event's daily cap is already used. */
    private boolean takeEventSlot(UUID eventId, LocalDate day) {
        List<Integer> row = jdbc.queryForList("""
                INSERT INTO face_search_usage (event_id, day, searches) VALUES (?, ?, 1)
                ON CONFLICT (event_id, day) DO UPDATE SET searches = face_search_usage.searches + 1
                    WHERE face_search_usage.searches < ?
                RETURNING searches""", Integer.class, eventId, day, config.eventDailyCap());
        return !row.isEmpty();
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    void evictIdleIps() {
        long now = System.currentTimeMillis();
        for (Map<String, ArrayDeque<Long>> byKey : List.of(searchesByIp, searchesByVisitor)) {
            byKey.values().removeIf(t -> {
                synchronized (t) {
                    return t.isEmpty() || now - t.peekLast() > HOUR_MS;
                }
            });
        }
    }

    // ---- Nightly check ----

    /**
     * Deletes collections whose event is gone, archived (photos deleted) or no longer has the add-on: the safety net
     * for a provider call that failed earlier. Returns how many were deleted.
     */
    @Scheduled(cron = "0 47 3 * * *", zone = "UTC")
    public int sweepOrphanCollections() {
        try {
            int stale = matches.deleteOrphans();
            if (stale > 0) {
                log.info("Face search: deleted {} remembered matches of galleries without the add-on", stale);
            }
        } catch (Exception e) {
            log.warn("Face search: could not clean up remembered matches: {}", e.toString());
        }
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
