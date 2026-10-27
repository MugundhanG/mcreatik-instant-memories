package com.mcreatik.gallery.photo;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

/**
 * Dashboard photo listing: filter by camera, likes, hidden and day; sort by time or camera. Offset paging: the
 * dashboard asks for the next page at "photos it already has", so deleting photos never skips or repeats any.
 */
@Repository
public class AdminPhotoSearch {

    public enum Sort {
        /** Newest capture first. */
        NEWEST,
        OLDEST,
        /** Grouped by camera (in the order the cameras were added), then by capture time. */
        CAMERA
    }

    public record Filter(UUID uploaderId, Boolean liked, Boolean hidden, LocalDate day) {
        public static final Filter NONE = new Filter(null, null, null, null);
    }

    /** When the photo was taken: the camera's clock, else the upload time. */
    static final String TAKEN_AT = "coalesce(p.captured_at, p.created_at)";

    /**
     * The day it was taken, as the camera shows it. EXIF times carry no zone and are stored as UTC, so their UTC
     * date is the camera's date; photos without EXIF fall back to the upload day in India.
     */
    static final String DAY = "coalesce(cast(timezone('UTC', p.captured_at) as date), "
            + "cast(timezone('Asia/Kolkata', p.created_at) as date))";

    private final EntityManager em;

    public AdminPhotoSearch(EntityManager em) {
        this.em = em;
    }

    public List<Photo> find(UUID eventId, Filter filter, Sort sort, int offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        String order = switch (sort) {
            case NEWEST -> TAKEN_AT + " DESC, p.id DESC";
            case OLDEST -> TAKEN_AT + " ASC, p.id ASC";
            case CAMERA -> "u.created_at ASC NULLS LAST, u.id, " + TAKEN_AT + " ASC, p.id ASC";
        };
        String sql = "SELECT p.* FROM photos p LEFT JOIN uploaders u ON u.id = p.uploader_id WHERE "
                + where(eventId, filter, params) + " ORDER BY " + order + " OFFSET :offset LIMIT :limit";
        Query q = em.createNativeQuery(sql, Photo.class);
        params.forEach(q::setParameter);
        q.setParameter("offset", offset);
        q.setParameter("limit", limit);
        @SuppressWarnings("unchecked")
        List<Photo> result = q.getResultList();
        return result;
    }

    public record DayCount(LocalDate day, long photos) {
    }

    /** Days that have photos, oldest first, for the date filter. */
    public List<DayCount> days(UUID eventId) {
        Query q = em.createNativeQuery("SELECT " + DAY + " AS day, count(*) FROM photos p WHERE p.event_id = :event "
                + "GROUP BY 1 ORDER BY 1");
        q.setParameter("event", eventId);
        List<DayCount> days = new ArrayList<>();
        for (Object row : q.getResultList()) {
            Object[] r = (Object[]) row;
            LocalDate day = r[0] instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) r[0];
            days.add(new DayCount(day, ((Number) r[1]).longValue()));
        }
        return days;
    }

    private static String where(UUID eventId, Filter f, Map<String, Object> params) {
        StringBuilder w = new StringBuilder("p.event_id = :event");
        params.put("event", eventId);
        if (f.uploaderId() != null) {
            w.append(" AND p.uploader_id = :uploader");
            params.put("uploader", f.uploaderId());
        }
        if (f.liked() != null) {
            w.append(f.liked() ? " AND p.liked" : " AND NOT p.liked");
        }
        if (f.hidden() != null) {
            w.append(f.hidden() ? " AND p.hidden" : " AND NOT p.hidden");
        }
        if (f.day() != null) {
            w.append(" AND ").append(DAY).append(" = :day");
            params.put("day", f.day());
        }
        return w.toString();
    }
}
