package com.mcreatik.gallery.analytics;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.mcreatik.gallery.common.ApiException;
import com.mcreatik.gallery.photo.PhotoMapper;

@Service
public class GuestActivityService {

    /** One batch from a guest's browser (it sends every few seconds and when the page closes). */
    static final int MAX_BATCH = 50;
    private static final Pattern VISITOR_ID = Pattern.compile("^[A-Za-z0-9-]{8,40}$");
    private static final String ZONE = "Asia/Kolkata";

    public record Item(String type, UUID photoId, String source) {
    }

    private final JdbcTemplate jdbc;
    private final PhotoMapper photoMapper;

    public GuestActivityService(JdbcTemplate jdbc, PhotoMapper photoMapper) {
        this.jdbc = jdbc;
        this.photoMapper = photoMapper;
    }

    /** Stores a guest's batch. Unknown types and malformed items are skipped, never failing the batch. */
    public void record(UUID eventId, String visitorId, List<Item> items) {
        if (visitorId == null || !VISITOR_ID.matcher(visitorId).matches()) {
            throw ApiException.badRequest("INVALID_VISITOR", "visitorId is missing or malformed");
        }
        if (items == null || items.isEmpty()) {
            return;
        }
        if (items.size() > MAX_BATCH) {
            throw ApiException.badRequest("BATCH_TOO_LARGE", "At most " + MAX_BATCH + " items per request");
        }
        List<Object[]> rows = new java.util.ArrayList<>();
        for (Item item : items) {
            Object[] row = row(eventId, visitorId, item);
            if (row == null) {
                continue;
            }
            String type = (String) row[1];
            if (GuestActivity.Type.LIKE.name().equals(type) || GuestActivity.Type.UNLIKE.name().equals(type)) {
                // One like per guest and photo: a repeat changes nothing, UNLIKE takes it back.
                if (item.photoId() == null) {
                    continue;
                }
                jdbc.update("DELETE FROM guest_activity WHERE event_id = ? AND photo_id = ? AND visitor_id = ? AND type = 'LIKE'",
                        eventId, item.photoId(), visitorId);
                if (GuestActivity.Type.LIKE.name().equals(type)) {
                    // Written now, not in the batch, so a repeat in the same batch finds it and replaces it.
                    jdbc.update("INSERT INTO guest_activity (event_id, type, visitor_id, photo_id, source) VALUES (?, ?, ?, ?, ?)", row);
                }
                continue;
            }
            rows.add(row);
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO guest_activity (event_id, type, visitor_id, photo_id, source) VALUES (?, ?, ?, ?, ?)",
                    rows);
        }
    }

    private static Object[] row(UUID eventId, String visitorId, Item item) {
        GuestActivity.Type type;
        try {
            type = GuestActivity.Type.valueOf(String.valueOf(item.type()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        String source = type == GuestActivity.Type.VISIT ? ("qr".equals(item.source()) ? "qr" : "link") : null;
        return new Object[] {eventId, type.name(), visitorId, item.photoId(), source};
    }

    public GuestActivity.Summary summary(UUID eventId) {
        GuestActivity.Totals totals = jdbc.queryForObject("""
                SELECT count(DISTINCT visitor_id),
                       count(*) FILTER (WHERE type = 'VISIT' AND source = 'qr'),
                       count(*) FILTER (WHERE type = 'VISIT'),
                       count(*) FILTER (WHERE type = 'PHOTO_VIEW'),
                       count(*) FILTER (WHERE type = 'DOWNLOAD'),
                       count(DISTINCT visitor_id) FILTER (WHERE type = 'DOWNLOAD'),
                       count(*) FILTER (WHERE type = 'SHARE'),
                       count(*) FILTER (WHERE type = 'INSTAGRAM'),
                       count(*) FILTER (WHERE type = 'CONTACT'),
                       count(*) FILTER (WHERE type = 'LIKE')
                FROM guest_activity WHERE event_id = ?""",
                (rs, n) -> new GuestActivity.Totals(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getLong(10)),
                eventId);

        List<GuestActivity.Hour> hours = jdbc.query("""
                SELECT to_char(date_trunc('hour', created_at AT TIME ZONE '%s'), 'YYYY-MM-DD"T"HH24:00') AS h,
                       count(DISTINCT visitor_id),
                       count(*) FILTER (WHERE type = 'PHOTO_VIEW'),
                       count(*) FILTER (WHERE type = 'DOWNLOAD')
                FROM guest_activity WHERE event_id = ?
                GROUP BY h ORDER BY h""".formatted(ZONE),
                (rs, n) -> new GuestActivity.Hour(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
                eventId);

        List<GuestActivity.TopPhoto> top = topPhotos(eventId, "downloads DESC, views DESC", null);
        Timestamp last = jdbc.queryForObject("SELECT max(created_at) FROM guest_activity WHERE event_id = ?",
                Timestamp.class, eventId);
        Instant lastAt = last == null ? null : last.toInstant();
        return new GuestActivity.Summary(totals, hours, top, lastAt, topPhotos(eventId, "likes DESC, downloads DESC", "likes"),
                topPhotos(eventId, "downloads DESC, views DESC", "downloads"), topPhotos(eventId, "views DESC, downloads DESC", "views"));
    }

    /** Photos still in the gallery (deleted or hidden ones drop out), ranked; {@code nonZero}: leave out photos at 0 of it. */
    private List<GuestActivity.TopPhoto> topPhotos(UUID eventId, String order, String nonZero) {
        return jdbc.query("""
                SELECT * FROM (
                    SELECT p.id, p.thumbnail_storage_key, p.original_file_name,
                           count(*) FILTER (WHERE a.type = 'PHOTO_VIEW') AS views,
                           count(*) FILTER (WHERE a.type = 'DOWNLOAD') AS downloads,
                           count(*) FILTER (WHERE a.type = 'LIKE') AS likes
                    FROM guest_activity a
                    JOIN photos p ON p.id = a.photo_id AND p.event_id = a.event_id AND p.status = 'READY' AND NOT p.hidden AND p.gallery = 'GUEST'
                    WHERE a.event_id = ? AND a.photo_id IS NOT NULL
                    GROUP BY p.id, p.thumbnail_storage_key, p.original_file_name) t
                %s ORDER BY %s, id LIMIT 8""".formatted(nonZero == null ? "" : "WHERE " + nonZero + " > 0", order),
                (rs, n) -> new GuestActivity.TopPhoto(rs.getObject(1, UUID.class), photoMapper.url(rs.getString(2)),
                        rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getLong(6)),
                eventId);
    }
}
