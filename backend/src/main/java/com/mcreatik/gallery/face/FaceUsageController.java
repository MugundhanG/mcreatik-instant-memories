package com.mcreatik.gallery.face;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mcreatik.gallery.auth.AdminAccess;
import com.mcreatik.gallery.common.ApiException;

/**
 * McreatiK only: what face search really used, per event and per day, counted as AWS bills it (see
 * MeteredFaceSearchProvider). The dashboard turns the counts into an estimated cost with AWS's list prices; the AWS
 * bill stays the real figure.
 */
@RestController
@RequestMapping("/api/admin/face-usage")
public class FaceUsageController {

    /** One event's usage in the period. {@code eventName} etc. are null once the event is deleted. */
    public record EventUsage(UUID eventId, String eventName, String studioName, String eventType, LocalDate eventDate,
                             boolean paid, long photosScanned, long facesIndexed, long searches, LocalDate firstDay,
                             LocalDate lastDay) {
    }

    public record DayUsage(LocalDate day, long photosScanned, long facesIndexed, long searches) {
    }

    public record Usage(LocalDate from, LocalDate to, List<EventUsage> events, List<DayUsage> days) {
    }

    static final int MAX_DAYS = 400;

    private final JdbcTemplate jdbc;
    private final AdminAccess access;

    public FaceUsageController(JdbcTemplate jdbc, AdminAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    /** {@code from} / {@code to}: India dates, both included; by default the start of last month to today. */
    @GetMapping
    public Usage usage(Authentication auth,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        access.requireSuperAdmin(auth);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? today.minusMonths(1).withDayOfMonth(1) : from;
        if (start.isAfter(end) || start.plusDays(MAX_DAYS).isBefore(end)) {
            throw ApiException.badRequest("INVALID_PERIOD", "Choose a period of up to " + MAX_DAYS + " days, oldest date first");
        }
        List<EventUsage> events = jdbc.query("""
                SELECT u.event_id, e.name, a.display_name, e.event_type, e.event_date, coalesce(e.face_search_paid, false),
                       sum(u.photos_scanned), sum(u.faces_indexed), sum(u.searches), min(u.day), max(u.day)
                FROM face_usage u
                LEFT JOIN events e ON e.id = u.event_id
                LEFT JOIN admin_users a ON a.id = e.owner_id
                WHERE u.day BETWEEN ? AND ?
                GROUP BY u.event_id, e.name, a.display_name, e.event_type, e.event_date, e.face_search_paid
                ORDER BY max(u.day) DESC, sum(u.photos_scanned) DESC""",
                (rs, n) -> new EventUsage(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getDate(5) == null ? null : rs.getDate(5).toLocalDate(), rs.getBoolean(6), rs.getLong(7),
                        rs.getLong(8), rs.getLong(9), rs.getDate(10).toLocalDate(), rs.getDate(11).toLocalDate()),
                Date.valueOf(start), Date.valueOf(end));
        List<DayUsage> days = jdbc.query("""
                SELECT day, sum(photos_scanned), sum(faces_indexed), sum(searches) FROM face_usage
                WHERE day BETWEEN ? AND ? GROUP BY day ORDER BY day""",
                (rs, n) -> new DayUsage(rs.getDate(1).toLocalDate(), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
                Date.valueOf(start), Date.valueOf(end));
        return new Usage(start, end, events, days);
    }
}
