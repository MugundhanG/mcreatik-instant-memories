package com.mcreatik.gallery.analytics;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.mcreatik.gallery.common.ApiException;
import com.mcreatik.gallery.photo.PhotoMapper;

/**
 * Guests ask the studio for the full-quality version of a photo and leave an email or a WhatsApp number: the studio
 * sends the photo and has a new contact (a booking lead).
 */
@Service
public class HqRequests {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,255}\\.[^@\\s]{2,}$");
    /** One guest can't flood a studio's list. */
    static final int MAX_PER_VISITOR = 20;

    public record Request(UUID id, UUID photoId, String thumbnailUrl, String fileName, String name, String contact,
                          String kind, boolean done, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final PhotoMapper photoMapper;

    public HqRequests(JdbcTemplate jdbc, PhotoMapper photoMapper) {
        this.jdbc = jdbc;
        this.photoMapper = photoMapper;
    }

    /** {@code contact}: an email, or a phone number (a 10-digit Indian number gets +91). */
    public void create(UUID eventId, UUID photoId, String rawName, String rawContact, String visitorId) {
        String contact = rawContact == null ? "" : rawContact.trim();
        String kind;
        if (EMAIL.matcher(contact).matches() && contact.length() <= 320) {
            kind = "EMAIL";
            contact = contact.toLowerCase(java.util.Locale.ROOT);
        } else {
            String digits = contact.replaceAll("[\\s()+-]", "");
            if (!digits.matches("\\d{10,15}")) {
                throw ApiException.badRequest("INVALID_CONTACT", "Enter your email, or your WhatsApp number with 10 digits");
            }
            kind = "WHATSAPP";
            contact = "+" + (digits.length() == 10 ? "91" + digits : digits);
        }
        if (photoId != null && jdbc.queryForObject(
                "SELECT count(*) FROM photos WHERE id = ? AND event_id = ? AND status = 'READY' AND NOT hidden",
                Integer.class, photoId, eventId) == 0) {
            throw ApiException.notFound("Photo");
        }
        String visitor = visitorId != null && visitorId.matches("^[A-Za-z0-9-]{8,40}$") ? visitorId : null;
        if (visitor != null && jdbc.queryForObject("SELECT count(*) FROM hq_requests WHERE event_id = ? AND visitor_id = ?",
                Integer.class, eventId, visitor) >= MAX_PER_VISITOR) {
            throw ApiException.conflict("TOO_MANY_REQUESTS", "You have already asked for many photos. The studio will contact you.");
        }
        String name = rawName == null || rawName.isBlank() ? null : rawName.trim();
        if (name != null && name.length() > 80) {
            name = name.substring(0, 80);
        }
        // Asking twice for the same photo with the same contact keeps one row.
        if (jdbc.queryForObject("SELECT count(*) FROM hq_requests WHERE event_id = ? AND contact = ? AND photo_id IS NOT DISTINCT FROM ?",
                Integer.class, eventId, contact, photoId) > 0) {
            return;
        }
        jdbc.update("INSERT INTO hq_requests (id, event_id, photo_id, name, contact, kind, visitor_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), eventId, photoId, name, contact, kind, visitor);
    }

    public List<Request> list(UUID eventId) {
        return jdbc.query("""
                SELECT r.id, r.photo_id, p.thumbnail_storage_key, p.original_file_name, r.name, r.contact, r.kind, r.done, r.created_at
                FROM hq_requests r LEFT JOIN photos p ON p.id = r.photo_id
                WHERE r.event_id = ? ORDER BY r.done, r.created_at DESC LIMIT 500""",
                (rs, n) -> new Request(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3) == null ? null : photoMapper.url(rs.getString(3)), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getBoolean(8), ((Timestamp) rs.getTimestamp(9)).toInstant()),
                eventId);
    }

    public void setDone(UUID eventId, UUID requestId, boolean done) {
        if (jdbc.update("UPDATE hq_requests SET done = ? WHERE id = ? AND event_id = ?", done, requestId, eventId) == 0) {
            throw ApiException.notFound("Request");
        }
    }
}
