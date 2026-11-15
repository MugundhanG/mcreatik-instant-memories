package com.mcreatik.gallery.face;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * "Remember me": the faces that matched a guest's selfie, kept under a random match id that only the guest's phone
 * knows. Database only (no engine calls). The selfie is not here, only face ids.
 */
@Service
public class GuestMatchService {

    /** At most this many of the best matches are kept as seeds for "Update my photos". */
    public static final int MAX_SEEDS = 5;

    public record Match(UUID id, UUID eventId, List<String> seedFaceIds, List<String> faceIds, Instant checkedAt) {
    }

    private final JdbcTemplate jdbc;

    public GuestMatchService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The database's clock, so "checked at" and "indexed at" are compared on the same clock. */
    public Instant now() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    public UUID create(UUID eventId, List<String> seeds, List<String> faceIds, Instant checkedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO guest_matches (id, event_id, seed_face_ids, face_ids, checked_at) VALUES (?, ?, ?, ?, ?)",
                id, eventId, seeds.toArray(new String[0]), faceIds.toArray(new String[0]), Timestamp.from(checkedAt));
        return id;
    }

    /** The match, only if it belongs to this event. */
    public Optional<Match> find(UUID eventId, UUID matchId) {
        List<Match> rows = jdbc.query("""
                SELECT id, event_id, seed_face_ids, face_ids, checked_at FROM guest_matches WHERE id = ? AND event_id = ?""",
                (rs, n) -> new Match(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), strings(rs.getArray(3)),
                        strings(rs.getArray(4)), rs.getTimestamp(5).toInstant()),
                matchId, eventId);
        return rows.stream().findFirst();
    }

    /** Photos were indexed after the match was last checked (visible guest photos only). */
    public boolean hasNew(UUID matchId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM guest_matches m JOIN photos p ON p.event_id = m.event_id
                    WHERE m.id = ? AND p.gallery = 'GUEST' AND p.status = 'READY' AND NOT p.hidden
                      AND p.face_indexed_at IS NOT NULL AND p.face_indexed_at > m.checked_at)""", Boolean.class, matchId));
    }

    public void update(UUID matchId, List<String> seeds, List<String> faceIds, Instant checkedAt) {
        jdbc.update("UPDATE guest_matches SET seed_face_ids = ?, face_ids = ?, checked_at = ? WHERE id = ?",
                seeds.toArray(new String[0]), faceIds.toArray(new String[0]), Timestamp.from(checkedAt), matchId);
    }

    /** "Forget me". */
    public void delete(UUID eventId, UUID matchId) {
        jdbc.update("DELETE FROM guest_matches WHERE id = ? AND event_id = ?", matchId, eventId);
    }

    /** The add-on is off, or the event or its photos are gone. */
    public void deleteForEvent(UUID eventId) {
        jdbc.update("DELETE FROM guest_matches WHERE event_id = ?", eventId);
    }

    /** Nightly safety net: matches of events that no longer have the add-on or whose photos are deleted. */
    public int deleteOrphans() {
        return jdbc.update("""
                DELETE FROM guest_matches WHERE event_id IN (
                    SELECT id FROM events WHERE NOT face_search_enabled OR photos_deleted_at IS NOT NULL OR status = 'ARCHIVED')""");
    }

    private static List<String> strings(Array array) throws java.sql.SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
