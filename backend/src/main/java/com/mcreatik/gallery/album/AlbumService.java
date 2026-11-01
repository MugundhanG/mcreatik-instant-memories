package com.mcreatik.gallery.album;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mcreatik.gallery.common.ApiException;
import com.mcreatik.gallery.photo.PhotoMapper;

/** Photo categories, the couple's logins and their album selection, per event. */
@Service
public class AlbumService {

    public static final List<String> DEFAULT_CATEGORIES =
            List.of("Couple", "Family & Groups", "Rituals", "Cake cutting", "Main event", "Candid");
    static final int MIN_PASSWORD = 10;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final PhotoMapper photoMapper;

    public AlbumService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, PhotoMapper photoMapper) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.photoMapper = photoMapper;
    }

    // ---- categories ----

    public void createDefaultCategories(UUID eventId) {
        for (int i = 0; i < DEFAULT_CATEGORIES.size(); i++) {
            jdbc.update("INSERT INTO event_categories (id, event_id, name, position) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), eventId, DEFAULT_CATEGORIES.get(i), i + 1);
        }
    }

    /** With photo counts; hidden photos count too here (the studio sees them), see {@link #visibleCategories}. */
    public List<AlbumDtos.Category> categories(UUID eventId) {
        return categories(eventId, false);
    }

    /** Categories as the couple sees them: counts of photos they can open. */
    public List<AlbumDtos.Category> visibleCategories(UUID eventId) {
        return categories(eventId, true);
    }

    private List<AlbumDtos.Category> categories(UUID eventId, boolean visibleOnly) {
        String photoFilter = visibleOnly ? " AND p.status = 'READY' AND NOT p.hidden" : "";
        return jdbc.query("""
                SELECT c.id, c.name, c.position, count(p.id)
                FROM event_categories c LEFT JOIN photos p ON p.category_id = c.id%s
                WHERE c.event_id = ?
                GROUP BY c.id, c.name, c.position ORDER BY c.position, c.created_at""".formatted(photoFilter),
                (rs, n) -> new AlbumDtos.Category(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3),
                        rs.getLong(4)),
                eventId);
    }

    @Transactional
    public AlbumDtos.Category createCategory(UUID eventId, String rawName) {
        String name = categoryName(rawName);
        Integer next = jdbc.queryForObject("SELECT coalesce(max(position), 0) + 1 FROM event_categories WHERE event_id = ?",
                Integer.class, eventId);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO event_categories (id, event_id, name, position) VALUES (?, ?, ?, ?)", id, eventId, name, next);
        return new AlbumDtos.Category(id, name, next, 0);
    }

    public void renameCategory(UUID eventId, UUID categoryId, String rawName) {
        if (jdbc.update("UPDATE event_categories SET name = ? WHERE id = ? AND event_id = ?", categoryName(rawName),
                categoryId, eventId) == 0) {
            throw ApiException.notFound("Category");
        }
    }

    /** Its photos stay in the event, just "not sorted". */
    public void deleteCategory(UUID eventId, UUID categoryId) {
        if (jdbc.update("DELETE FROM event_categories WHERE id = ? AND event_id = ?", categoryId, eventId) == 0) {
            throw ApiException.notFound("Category");
        }
    }

    /** Moves photos of this event into a category (null: not sorted). Returns how many moved. */
    public int assign(UUID eventId, List<UUID> photoIds, UUID categoryId) {
        if (photoIds == null || photoIds.isEmpty()) {
            return 0;
        }
        if (photoIds.size() > 1000) {
            throw ApiException.badRequest("TOO_MANY", "Move at most 1,000 photos at a time");
        }
        requireCategory(eventId, categoryId);
        UUID[] ids = photoIds.toArray(UUID[]::new);
        return jdbc.update("UPDATE photos SET category_id = ?, updated_at = now() WHERE event_id = ? AND id = ANY (?)",
                ps -> {
                    ps.setObject(1, categoryId);
                    ps.setObject(2, eventId);
                    ps.setArray(3, ps.getConnection().createArrayOf("uuid", ids));
                });
    }

    public void requireCategory(UUID eventId, UUID categoryId) {
        if (categoryId != null && jdbc.queryForObject(
                "SELECT count(*) FROM event_categories WHERE id = ? AND event_id = ?", Long.class, categoryId, eventId) == 0) {
            throw ApiException.badRequest("INVALID_CATEGORY", "Unknown category for this event");
        }
    }

    private static String categoryName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > 60) {
            throw ApiException.badRequest("INVALID_NAME", "Category names are 1 to 60 characters");
        }
        return name;
    }

    // ---- clients ----

    public List<AlbumDtos.Client> clients(UUID eventId) {
        return jdbc.query("""
                SELECT id, name, email, must_change_password, disabled, created_at, last_login_at
                FROM event_clients WHERE event_id = ? ORDER BY created_at""", (rs, n) -> new AlbumDtos.Client(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5),
                instant(rs.getTimestamp(6)), instant(rs.getTimestamp(7))), eventId);
    }

    public AlbumDtos.Client createClient(UUID eventId, AlbumDtos.CreateClientRequest request) {
        String name = request.name() == null ? "" : request.name().trim();
        String email = request.email() == null ? "" : request.email().trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.length() > 120) {
            throw ApiException.badRequest("INVALID_NAME", "Enter the client's name");
        }
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || email.length() > 255) {
            throw ApiException.badRequest("INVALID_EMAIL", "Enter a valid email address");
        }
        password(request.password());
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO event_clients (id, event_id, name, email, password_hash) VALUES (?, ?, ?, ?, ?)",
                    id, eventId, name, email, passwordEncoder.encode(request.password()));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("EMAIL_TAKEN", "This email already has a client login");
        }
        return clients(eventId).stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    public void updateClient(UUID eventId, UUID clientId, AlbumDtos.UpdateClientRequest request) {
        requireClient(eventId, clientId);
        if (request.newPassword() != null) {
            password(request.newPassword());
            jdbc.update("UPDATE event_clients SET password_hash = ?, must_change_password = true WHERE id = ?",
                    passwordEncoder.encode(request.newPassword()), clientId);
        }
        if (request.disabled() != null) {
            jdbc.update("UPDATE event_clients SET disabled = ? WHERE id = ?", request.disabled(), clientId);
        }
    }

    public void deleteClient(UUID eventId, UUID clientId) {
        requireClient(eventId, clientId);
        jdbc.update("DELETE FROM event_clients WHERE id = ?", clientId);
    }

    private void requireClient(UUID eventId, UUID clientId) {
        if (jdbc.queryForObject("SELECT count(*) FROM event_clients WHERE id = ? AND event_id = ?", Long.class,
                clientId, eventId) == 0) {
            throw ApiException.notFound("Client");
        }
    }

    static void password(String password) {
        if (password == null || password.length() < MIN_PASSWORD || password.length() > 200) {
            throw ApiException.badRequest("WEAK_PASSWORD", "Passwords need at least " + MIN_PASSWORD + " characters");
        }
    }

    // ---- album ----

    public Integer albumLimit(UUID eventId) {
        return jdbc.queryForObject("SELECT album_limit FROM events WHERE id = ?", Integer.class, eventId);
    }

    public void setAlbumLimit(UUID eventId, Integer limit) {
        if (limit != null && (limit < 1 || limit > 100_000)) {
            throw ApiException.badRequest("INVALID_LIMIT", "The album limit must be a positive number");
        }
        jdbc.update("UPDATE events SET album_limit = ? WHERE id = ?", limit, eventId);
    }

    public AlbumDtos.Album album(UUID eventId) {
        List<AlbumDtos.SelectedPhoto> selected = jdbc.query("""
                SELECT p.id, p.original_file_name, p.thumbnail_storage_key, s.created_at, c.name, p.category_id
                FROM album_selections s
                JOIN photos p ON p.id = s.photo_id
                LEFT JOIN event_clients c ON c.id = s.client_id
                WHERE s.event_id = ?
                ORDER BY coalesce(p.captured_at, p.created_at), p.id""", (rs, n) -> new AlbumDtos.SelectedPhoto(
                rs.getObject(1, UUID.class), rs.getString(2), photoMapper.url(rs.getString(3)),
                instant(rs.getTimestamp(4)), rs.getString(5), rs.getObject(6, UUID.class)), eventId);
        return new AlbumDtos.Album(albumLimit(eventId), selected);
    }

    public long selectedCount(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM album_selections WHERE event_id = ?", Long.class, eventId);
    }

    public List<UUID> selectedIds(UUID eventId, List<UUID> photoIds) {
        if (photoIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT photo_id FROM album_selections WHERE event_id = ? AND photo_id = ANY (?)",
                ps -> {
                    ps.setObject(1, eventId);
                    ps.setArray(2, ps.getConnection().createArrayOf("uuid", photoIds.toArray()));
                }, (rs, n) -> rs.getObject(1, UUID.class));
    }

    /** The couple selects or unselects a photo for the album, within the studio's limit. */
    @Transactional
    public long select(UUID eventId, UUID clientId, UUID photoId, boolean selected) {
        if (!selected) {
            jdbc.update("DELETE FROM album_selections WHERE event_id = ? AND photo_id = ?", eventId, photoId);
            return selectedCount(eventId);
        }
        Long visible = jdbc.queryForObject("""
                SELECT count(*) FROM photos WHERE id = ? AND event_id = ? AND status = 'READY' AND NOT hidden""",
                Long.class, photoId, eventId);
        if (visible == 0) {
            throw ApiException.notFound("Photo");
        }
        // Serialise selections per event so two phones cannot both take the last place.
        jdbc.queryForObject("SELECT id FROM events WHERE id = ? FOR UPDATE", UUID.class, eventId);
        Integer limit = albumLimit(eventId);
        long count = selectedCount(eventId);
        boolean already = jdbc.queryForObject(
                "SELECT count(*) FROM album_selections WHERE event_id = ? AND photo_id = ?", Long.class, eventId, photoId) > 0;
        if (already) {
            return count;
        }
        if (limit != null && count >= limit) {
            throw new ApiException(HttpStatus.CONFLICT, "ALBUM_LIMIT_REACHED",
                    "Your album already has " + limit + " photos. Unselect one to choose another.");
        }
        jdbc.update("INSERT INTO album_selections (event_id, photo_id, client_id) VALUES (?, ?, ?)", eventId, photoId, clientId);
        return count + 1;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
