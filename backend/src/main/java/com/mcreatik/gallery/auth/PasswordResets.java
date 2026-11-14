package com.mcreatik.gallery.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mcreatik.gallery.config.GalleryProperties;
import com.mcreatik.gallery.mail.Mailer;

/**
 * "Forgot password" for studios and couples: emails a temporary password from connect@mcreatik.com.
 * <ul>
 * <li>The current password keeps working; the temporary one works alongside it for 30 minutes and replaces it only
 * when used, so a stranger who types someone's email cannot lock them out.</li>
 * <li>Only the temporary password's hash is stored.</li>
 * <li>The caller always gets the same answer, so it cannot learn which emails have accounts. The email is sent in
 * the background, so the response time does not give it away either.</li>
 * <li>At most one email per account every 2 minutes.</li>
 * </ul>
 */
@Service
public class PasswordResets {

    static final Duration VALID_FOR = Duration.ofMinutes(30);
    static final Duration THROTTLE = Duration.ofMinutes(2);
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AdminUserRepository users;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final Mailer mailer;
    private final GalleryProperties properties;

    public PasswordResets(AdminUserRepository users, JdbcTemplate jdbc, PasswordEncoder passwordEncoder, Mailer mailer,
                          GalleryProperties properties) {
        this.users = users;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.mailer = mailer;
        this.properties = properties;
    }

    /** Readable temporary password: no look-alike characters (0/O, 1/l), same as the dashboard's. */
    static String temporaryPassword() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String clean(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private String loginUrl(String path) {
        return GalleryProperties.stripTrailingSlash(properties.publicGalleryBaseUrl()) + path;
    }

    private static boolean throttled(Instant lastRequest, Instant now) {
        return lastRequest != null && lastRequest.isAfter(now.minus(THROTTLE));
    }

    /** Studio or McreatiK login. */
    @Transactional
    public void requestForStudio(String rawEmail) {
        String temporary = temporaryPassword();
        String hash = passwordEncoder.encode(temporary); // always hashed, so unknown emails take as long
        Instant now = Instant.now();
        AdminUser user = users.findByEmail(clean(rawEmail)).orElse(null);
        if (user == null || user.isDisabled() || throttled(user.getResetRequestedAt(), now)) {
            return;
        }
        user.startReset(hash, now.plus(VALID_FOR), now);
        users.save(user);
        sendLater(user.getEmail(), message(user.getDisplayName(), temporary, loginUrl("/admin/login")));
    }

    /** The couple's login: only while their event is open and McreatiK has not turned their gallery off. */
    @Transactional
    public void requestForClient(String rawEmail) {
        String temporary = temporaryPassword();
        String hash = passwordEncoder.encode(temporary);
        Instant now = Instant.now();
        record Row(UUID id, String name, String email, Instant requestedAt) {
        }
        List<Row> rows = jdbc.query("""
                SELECT c.id, c.name, c.email, c.reset_requested_at
                FROM event_clients c JOIN events e ON e.id = c.event_id
                WHERE lower(c.email) = ? AND NOT c.disabled AND e.status <> 'ARCHIVED' AND e.client_gallery_enabled""",
                (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()), clean(rawEmail));
        // One email may log in to several events: every login gets the same temporary password, in one email.
        List<Row> due = rows.stream().filter(r -> !throttled(r.requestedAt(), now)).toList();
        if (due.isEmpty()) {
            return;
        }
        for (Row r : due) {
            jdbc.update("""
                    UPDATE event_clients SET reset_password_hash = ?, reset_expires_at = ?, reset_requested_at = ?
                    WHERE id = ?""", hash, java.sql.Timestamp.from(now.plus(VALID_FOR)), java.sql.Timestamp.from(now), r.id());
        }
        Row c = due.getFirst();
        sendLater(c.email(), message(c.name(), temporary, loginUrl("/client")));
    }

    private void sendLater(String to, String body) {
        Thread.ofVirtual().name("password-reset-mail").start(() -> mailer.send(to, "Your McreatiK temporary password",
                body + "\n" + com.mcreatik.gallery.mail.MailSignature.text(), com.mcreatik.gallery.mail.MailSignature.html(body,
                        com.mcreatik.gallery.mail.MailSignature.logoUrl(properties.publicGalleryBaseUrl()))));
    }

    static String message(String name, String temporary, String loginUrl) {
        return """
                Hi %s,

                We received a request to reset the password for your McreatiK Instant Memories login.

                Temporary password: %s

                It works for 30 minutes. Log in here: %s
                You will then choose a new password.

                If you didn't ask for this, you can ignore this email. Your current password still works.
                """.formatted(name == null || name.isBlank() ? "there" : name, temporary, loginUrl);
    }
}
