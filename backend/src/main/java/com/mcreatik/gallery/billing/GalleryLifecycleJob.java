package com.mcreatik.gallery.billing;

import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mcreatik.gallery.config.GalleryProperties;
import com.mcreatik.gallery.event.EventService;
import com.mcreatik.gallery.mail.Mailer;

/**
 * Once a day (India morning), for studios that pay (not McreatiK's own or unlimited studios):
 * <ul>
 * <li><b>Storage of live events:</b> a Main or Quick event that still uses more than its storage
 * {@value #STORAGE_GRACE_DAYS} days after its date has its couple's gallery paused (the guests' gallery is not
 * touched) and the studio is emailed; buying storage turns it back on.</li>
 * <li><b>Active period:</b> one email {@value #WARN_DAYS} days before a gallery closes and one when it has closed
 * (it is deleted {@value EventService#DELETE_AFTER_DAYS} days later unless kept open longer).</li>
 * </ul>
 */
@Component
public class GalleryLifecycleJob {

    private static final Logger log = LoggerFactory.getLogger(GalleryLifecycleJob.class);
    static final int STORAGE_GRACE_DAYS = 14;
    static final int WARN_DAYS = 14;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final JdbcTemplate jdbc;
    private final Mailer mailer;
    private final GalleryProperties properties;

    public GalleryLifecycleJob(JdbcTemplate jdbc, Mailer mailer, GalleryProperties properties) {
        this.jdbc = jdbc;
        this.mailer = mailer;
        this.properties = properties;
    }

    private record Row(UUID eventId, String eventName, String email, String studio, LocalDate date) {
    }

    private static final String PAYING = "u.role <> 'SUPER_ADMIN' AND u.plan <> 'UNLIMITED' AND NOT u.disabled";

    /** 09:45 in India. */
    @Scheduled(cron = "0 15 4 * * *", zone = "UTC")
    public void run() {
        LocalDate today = BillingService.today();
        lockUnpaidStorage(today);
        warnBeforeExpiry(today);
        noticeExpired(today);
    }

    /** How many months "Keep more months" adds (McreatiK's setting). */
    private int months() {
        Integer m = jdbc.queryForObject("SELECT active_months FROM billing_settings LIMIT 1", Integer.class);
        return m == null ? 3 : m;
    }

    private String dashboard() {
        return properties.publicGalleryBaseUrl().replaceAll("/+$", "") + "/admin/events/";
    }

    void lockUnpaidStorage(LocalDate today) {
        List<Row> rows = jdbc.query("""
                UPDATE events e SET client_gallery_enabled = false, storage_locked = true
                FROM admin_users u
                WHERE u.id = e.owner_id AND %s AND e.photos_deleted_at IS NULL AND e.event_type IN ('MAIN', 'QUICK') AND NOT e.storage_locked
                  AND e.client_gallery_enabled AND e.event_date < ?
                  AND (SELECT coalesce(sum(p.media_bytes + CASE WHEN p.original_stored THEN p.file_size ELSE 0 END), 0)
                       FROM photos p WHERE p.event_id = e.id) > e.storage_quota_gb::bigint * ?
                RETURNING e.id, e.name, u.email, u.display_name, e.event_date""".formatted(PAYING),
                (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getDate(5).toLocalDate()), Date.valueOf(today.minusDays(STORAGE_GRACE_DAYS)), BillingService.GB);
        for (Row r : rows) {
            log.info("Storage: couple's gallery of event {} paused (storage not added)", r.eventId());
            mailer.send(r.email(), "Add storage to reopen the couple's gallery: " + r.eventName(), """
                    Hello %s,

                    "%s" uses more storage than its plan includes, so the couple's gallery is paused until storage is added.
                    The guests' live gallery and all photos are untouched.

                    Open the event and press Add storage; the couple's gallery opens again straight away:
                    %s%s

                    McreatiK Instant Memories
                    """.formatted(r.studio(), r.eventName(), dashboard(), r.eventId()));
        }
    }

    void warnBeforeExpiry(LocalDate today) {
        List<Row> rows = jdbc.query("""
                UPDATE events e SET expiry_warned_for = e.active_until
                FROM admin_users u
                WHERE u.id = e.owner_id AND %s AND e.photos_deleted_at IS NULL AND e.active_until BETWEEN ? AND ?
                  AND e.expiry_warned_for IS DISTINCT FROM e.active_until
                RETURNING e.id, e.name, u.email, u.display_name, e.active_until""".formatted(PAYING),
                (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getDate(5).toLocalDate()), Date.valueOf(today), Date.valueOf(today.plusDays(WARN_DAYS)));
        for (Row r : rows) {
            mailer.send(r.email(), "\"" + r.eventName() + "\" closes on " + DAY.format(r.date()), """
                    Hello %s,

                    The galleries of "%s" (guests and couple) are open until %s.
                    To keep them open for %d more months, open the event and press Keep %d more months:
                    %s%s

                    If you don't, the galleries close on that day and the photos are deleted for good on %s.
                    Remind your couple to download their photos before then.

                    McreatiK Instant Memories
                    """.formatted(r.studio(), r.eventName(), DAY.format(r.date()), months(), months(), dashboard(), r.eventId(),
                    DAY.format(r.date().plusDays(EventService.DELETE_AFTER_DAYS))));
        }
    }

    void noticeExpired(LocalDate today) {
        List<Row> rows = jdbc.query("""
                UPDATE events e SET expiry_notified_for = e.active_until
                FROM admin_users u
                WHERE u.id = e.owner_id AND %s AND e.photos_deleted_at IS NULL AND e.active_until < ?
                  AND e.expiry_notified_for IS DISTINCT FROM e.active_until
                RETURNING e.id, e.name, u.email, u.display_name, e.active_until""".formatted(PAYING),
                (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getDate(5).toLocalDate()), Date.valueOf(today));
        for (Row r : rows) {
            mailer.send(r.email(), "\"" + r.eventName() + "\" has closed", """
                    Hello %s,

                    The galleries of "%s" closed on %s. Guests and the couple now see "This gallery has expired".
                    The photos are deleted for good on %s. You can still keep it for %d more months until then:
                    %s%s

                    McreatiK Instant Memories
                    """.formatted(r.studio(), r.eventName(), DAY.format(r.date()),
                    DAY.format(r.date().plusDays(EventService.DELETE_AFTER_DAYS)), months(), dashboard(), r.eventId()));
        }
    }
}
