package com.mcreatik.gallery.face;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Counts what face search uses on the engine, per event and day, so McreatiK can compare the real usage of each event
 * with what it charges and with the AWS bill (McreatiK's Face search usage page). It counts what AWS bills: a photo
 * scanned (IndexFaces, one image, even when it finds no face), the faces it stored, and a guest search (SearchFacesByImage
 * with a selfie, or SearchFaces for an automatic update). Collections, deletes and listings are free and not counted.
 * Counting never gets in the way of face search: a failed count is logged and dropped.
 */
public class MeteredFaceSearchProvider implements FaceSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(MeteredFaceSearchProvider.class);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final String EVENT_MARK = "-event-";

    private final FaceSearchProvider engine;
    private final JdbcTemplate jdbc;

    public MeteredFaceSearchProvider(FaceSearchProvider engine, JdbcTemplate jdbc) {
        this.engine = engine;
        this.jdbc = jdbc;
    }

    /** The event a collection belongs to ({@code <prefix>-event-<id>}, see FaceSearchService), or null. */
    static UUID eventOf(String collection) {
        int at = collection == null ? -1 : collection.lastIndexOf(EVENT_MARK);
        if (at < 0) {
            return null;
        }
        try {
            return UUID.fromString(collection.substring(at + EVENT_MARK.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void count(String collection, int scanned, int faces, int searches) {
        UUID event = eventOf(collection);
        if (event == null) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO face_usage (event_id, day, photos_scanned, faces_indexed, searches) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (event_id, day) DO UPDATE SET
                        photos_scanned = face_usage.photos_scanned + EXCLUDED.photos_scanned,
                        faces_indexed = face_usage.faces_indexed + EXCLUDED.faces_indexed,
                        searches = face_usage.searches + EXCLUDED.searches""",
                    event, Date.valueOf(LocalDate.now(INDIA)), scanned, faces, searches);
        } catch (RuntimeException e) {
            log.warn("Face search usage not counted for event {}: {}", event, e.getMessage());
        }
    }

    @Override
    public void createCollection(String collection) {
        engine.createCollection(collection);
    }

    @Override
    public void deleteCollection(String collection) {
        engine.deleteCollection(collection);
    }

    @Override
    public List<String> indexFaces(String collection, UUID photoId, byte[] jpegBytes) {
        List<String> ids;
        try {
            ids = engine.indexFaces(collection, photoId, jpegBytes);
        } catch (NoFaceException e) {
            count(collection, 1, 0, 0); // the image was still scanned, and billed
            throw e;
        }
        count(collection, 1, ids == null ? 0 : ids.size(), 0);
        return ids;
    }

    @Override
    public List<FaceMatch> searchFaces(String collection, byte[] imageBytes, float threshold, int maxFaces) {
        List<FaceMatch> found = engine.searchFaces(collection, imageBytes, threshold, maxFaces);
        count(collection, 0, 0, 1);
        return found;
    }

    @Override
    public List<String> searchFacesByFaceId(String collection, String faceId, float threshold, int maxFaces) {
        List<String> found = engine.searchFacesByFaceId(collection, faceId, threshold, maxFaces);
        count(collection, 0, 0, 1);
        return found;
    }

    @Override
    public void deleteFaces(String collection, Collection<String> faceIds) {
        engine.deleteFaces(collection, faceIds);
    }

    @Override
    public List<String> listCollections(String prefix) {
        return engine.listCollections(prefix);
    }

    /** The engine underneath, for code that needs to know which one runs. */
    public FaceSearchProvider engine() {
        return engine;
    }
}
