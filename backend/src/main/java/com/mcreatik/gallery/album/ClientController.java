package com.mcreatik.gallery.album;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mcreatik.gallery.auth.JwtService;
import com.mcreatik.gallery.auth.StudioBranding;
import com.mcreatik.gallery.common.ApiException;
import com.mcreatik.gallery.download.ZipDownloads;
import com.mcreatik.gallery.event.Event;
import com.mcreatik.gallery.event.EventRepository;
import com.mcreatik.gallery.event.EventService;
import com.mcreatik.gallery.photo.Photo;
import com.mcreatik.gallery.photo.PhotoMapper;
import com.mcreatik.gallery.photo.PhotoRepository;
import com.mcreatik.gallery.photo.PhotoStatus;
import com.mcreatik.gallery.storage.StorageArea;
import com.mcreatik.gallery.storage.StorageService;

import jakarta.persistence.EntityManager;

/**
 * The couple's portal: their event's photos by category, in original quality, and the album selection.
 * Clients log in with the email and password the studio created for them.
 */
@RestController
@RequestMapping("/api/client")
public class ClientController {

    private static final int MAX_PAGE = 120;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;
    private final AlbumService album;
    private final EventRepository events;
    private final EventService eventService;
    private final PhotoRepository photos;
    private final PhotoMapper mapper;
    private final StorageService storage;
    private final StudioBranding branding;
    private final EntityManager em;
    private final ZipDownloads downloads;
    private final com.mcreatik.gallery.auth.PasswordResets resets;
    /** Used to keep login timing similar whether or not the email exists. */
    private final String dummyHash;

    public ClientController(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, JwtService jwt, AlbumService album,
                            EventRepository events, EventService eventService, PhotoRepository photos,
                            PhotoMapper mapper, StorageService storage, StudioBranding branding, EntityManager em,
                            ZipDownloads downloads, com.mcreatik.gallery.auth.PasswordResets resets) {
        this.downloads = downloads;
        this.resets = resets;
        this.dummyHash = passwordEncoder.encode("timing-equaliser");
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.album = album;
        this.events = events;
        this.eventService = eventService;
        this.photos = photos;
        this.mapper = mapper;
        this.storage = storage;
        this.branding = branding;
        this.em = em;
    }

    record ClientRow(UUID id, UUID eventId, String name, String email, String passwordHash, boolean mustChange,
                     boolean disabled, int tokenVersion, String resetHash, Instant resetExpiresAt) {

        /** The emailed temporary password's hash while it is still valid, else null. */
        String validResetHash(Instant now) {
            return resetHash != null && resetExpiresAt != null && resetExpiresAt.isAfter(now) ? resetHash : null;
        }
    }

    private ClientRow row(String where, Object arg) {
        List<ClientRow> rows = jdbc.query("""
                SELECT id, event_id, name, email, password_hash, must_change_password, disabled, token_version,
                       reset_password_hash, reset_expires_at
                FROM event_clients WHERE %s""".formatted(where), (rs, n) -> new ClientRow(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getBoolean(6), rs.getBoolean(7), rs.getInt(8), rs.getString(9),
                rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant()), arg);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** The logged-in client; a deleted or disabled login, or a changed password, stops older tokens at once. */
    private ClientRow current(Authentication auth) {
        ClientRow c = row("id = ?", UUID.fromString(auth.getName()));
        int version = auth instanceof JwtAuthenticationToken t ? JwtService.version(t.getToken()) : -1;
        if (c == null || c.disabled() || c.tokenVersion() != version) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Please log in again");
        }
        return c;
    }

    private Event event(ClientRow c) {
        Event e = events.findById(c.eventId()).orElseThrow(() -> ApiException.notFound("Event"));
        if (e.getStatus() == com.mcreatik.gallery.event.EventStatus.ARCHIVED) {
            throw ApiException.notFound("Event");
        }
        if (!e.isClientGalleryEnabled()) {
            throw eventService.galleryUnavailable(e);
        }
        return e;
    }

    public record LoginRequest(String email, String password) {
    }

    public record LoginResponse(String token, Instant expiresAt, String name, boolean mustChangePassword) {
    }

    @PostMapping("/auth/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        String email = request.email() == null ? "" : request.email().trim().toLowerCase(Locale.ROOT);
        ClientRow c = row("email = ?", email);
        String password = request.password() == null ? "" : request.password();
        boolean matches = passwordEncoder.matches(password, c != null ? c.passwordHash() : dummyHash);
        // "Forgot password": the emailed temporary password works too, until it expires.
        String resetHash = c == null || matches ? null : c.validResetHash(Instant.now());
        boolean temporary = resetHash != null && passwordEncoder.matches(password, resetHash);
        if (c == null || !(matches || temporary)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is not correct");
        }
        if (c.disabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "This login has been turned off by the studio");
        }
        event(c); // McreatiK may have turned this event's client gallery off
        int version = c.tokenVersion();
        boolean mustChange = c.mustChange();
        if (temporary) {
            // The temporary password becomes the password (other devices are logged out) and must be replaced now.
            jdbc.update("""
                    UPDATE event_clients SET password_hash = reset_password_hash, must_change_password = true,
                        token_version = token_version + 1, reset_password_hash = NULL, reset_expires_at = NULL
                    WHERE id = ?""", c.id());
            version++;
            mustChange = true;
        } else if (c.resetHash() != null) {
            jdbc.update("UPDATE event_clients SET reset_password_hash = NULL, reset_expires_at = NULL WHERE id = ?", c.id());
        }
        jdbc.update("UPDATE event_clients SET last_login_at = now() WHERE id = ?", c.id());
        JwtService.IssuedToken token = jwt.issueClient(c.id(), c.eventId(), c.email(), version);
        return new LoginResponse(token.token(), token.expiresAt(), c.name(), mustChange);
    }

    public record ForgotPasswordRequest(String email) {
    }

    /** "Forgot password": always 204, whether or not the email has a login. */
    @PostMapping("/auth/forgot-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgotPassword(@RequestBody ForgotPasswordRequest request) {
        if (request.email() != null && request.email().length() <= 255) {
            resets.requestForClient(request.email());
        }
    }

    public record EventInfo(String name, java.time.LocalDate eventDate, String coverUrl, StudioBranding.Brand brand) {
    }

    public record Me(String name, String email, boolean mustChangePassword, EventInfo event, Integer albumLimit,
                     long selectedCount, long totalPhotos, long uncategorizedPhotos, List<AlbumDtos.Category> categories) {
    }

    @GetMapping("/me")
    @Transactional(readOnly = true)
    public Me me(Authentication auth) {
        ClientRow c = current(auth);
        Event e = event(c);
        var pub = eventService.toPublic(e);
        long total = jdbc.queryForObject("SELECT count(*) FROM photos WHERE event_id = ? AND status = 'READY' AND NOT hidden",
                Long.class, e.getId());
        long unsorted = jdbc.queryForObject("""
                SELECT count(*) FROM photos WHERE event_id = ? AND status = 'READY' AND NOT hidden AND category_id IS NULL""",
                Long.class, e.getId());
        return new Me(c.name(), c.email(), c.mustChange(), new EventInfo(e.getName(), e.getEventDate(), pub.coverUrl(),
                branding.ofOwner(e.getOwnerId())), album.albumLimit(e.getId()), album.selectedCount(e.getId()), total,
                unsorted, album.visibleCategories(e.getId()));
    }

    public record ClientPhoto(UUID id, String thumbnailUrl, String webUrl, Integer width, Integer height,
                              boolean originalAvailable, boolean selected, UUID categoryId) {
    }

    public record ClientPhotoPage(List<ClientPhoto> photos, Integer nextOffset) {
    }

    /**
     * Oldest first (the order the day happened). {@code category}: a category id, or "none" for photos not sorted
     * yet; {@code selected=true}: only the album selection.
     */
    @GetMapping("/photos")
    @Transactional(readOnly = true)
    public ClientPhotoPage photos(Authentication auth, @RequestParam(required = false) String category,
                                  @RequestParam(defaultValue = "false") boolean selected,
                                  @RequestParam(defaultValue = "0") int offset,
                                  @RequestParam(defaultValue = "60") int limit) {
        ClientRow c = current(auth);
        Event e = event(c);
        int size = Math.clamp(limit, 1, MAX_PAGE);
        StringBuilder sql = new StringBuilder(
                "SELECT p.* FROM photos p WHERE p.event_id = :event AND p.status = 'READY' AND NOT p.hidden");
        if ("none".equals(category)) {
            sql.append(" AND p.category_id IS NULL");
        } else if (category != null && !category.isBlank()) {
            sql.append(" AND p.category_id = :category");
        }
        if (selected) {
            sql.append(" AND EXISTS (SELECT 1 FROM album_selections s WHERE s.event_id = p.event_id AND s.photo_id = p.id)");
        }
        sql.append(" ORDER BY coalesce(p.captured_at, p.created_at), p.id OFFSET :offset LIMIT :limit");
        var q = em.createNativeQuery(sql.toString(), Photo.class);
        q.setParameter("event", e.getId());
        if (category != null && !category.isBlank() && !"none".equals(category)) {
            try {
                q.setParameter("category", UUID.fromString(category));
            } catch (IllegalArgumentException bad) {
                throw ApiException.badRequest("INVALID_CATEGORY", "Unknown category");
            }
        }
        q.setParameter("offset", Math.max(0, offset));
        q.setParameter("limit", size + 1);
        @SuppressWarnings("unchecked")
        List<Photo> rows = q.getResultList();
        boolean more = rows.size() > size;
        List<Photo> page = more ? rows.subList(0, size) : rows;
        List<UUID> chosen = album.selectedIds(e.getId(), page.stream().map(Photo::getId).toList());
        return new ClientPhotoPage(page.stream().map(p -> new ClientPhoto(p.getId(),
                mapper.url(p.getThumbnailStorageKey()), mapper.url(p.getOptimizedStorageKey()), p.getWidth(),
                p.getHeight(), p.isOriginalStored(), chosen.contains(p.getId()), p.getCategoryId())).toList(),
                more ? Math.max(0, offset) + size : null);
    }

    public record SelectRequest(boolean selected) {
    }

    public record SelectResponse(long selectedCount, Integer albumLimit) {
    }

    @PutMapping("/photos/{photoId}/selected")
    public SelectResponse select(Authentication auth, @PathVariable UUID photoId, @RequestBody SelectRequest request) {
        ClientRow c = current(auth);
        Event e = event(c);
        long count = album.select(e.getId(), c.id(), photoId, request.selected());
        return new SelectResponse(count, album.albumLimit(e.getId()));
    }

    public record DownloadUrl(String url, boolean original) {
    }

    /** Original quality when the studio uploaded it, else the gallery version. */
    @GetMapping("/photos/{photoId}/download")
    @Transactional(readOnly = true)
    public DownloadUrl download(Authentication auth, @PathVariable UUID photoId) {
        ClientRow c = current(auth);
        Event e = event(c);
        Photo p = photos.findByIdAndEventId(photoId, e.getId())
                .filter(x -> x.getStatus() == PhotoStatus.READY && !x.isHidden())
                .orElseThrow(() -> ApiException.notFound("Photo"));
        if (p.isOriginalStored()) {
            return new DownloadUrl(storage.presignDownload(StorageArea.ORIGINALS, p.getStorageKey(), Duration.ofMinutes(10),
                    p.getOriginalFileName()), true);
        }
        return new DownloadUrl(mapper.url(p.getOptimizedStorageKey()), false);
    }

    /**
     * Several photos as one ZIP: {@code album} true for the album selection, else {@code photoIds}, else a whole
     * {@code category} (an id, "none" for not sorted, or "all").
     */
    public record ZipRequest(Boolean album, List<UUID> photoIds, String category) {
    }

    @PostMapping("/downloads")
    public ZipDownloads.Prepared zip(Authentication auth, @RequestBody ZipRequest request) {
        ClientRow c = current(auth);
        Event e = event(c);
        List<UUID> ids;
        String name = e.getName();
        if (Boolean.TRUE.equals(request.album())) {
            ids = album.selectedPhotoIds(e.getId());
            name += " album";
        } else if (request.photoIds() != null && !request.photoIds().isEmpty()) {
            ids = request.photoIds();
        } else {
            ids = categoryIds(e.getId(), request.category());
        }
        return downloads.prepare(e.getId(), ids, false, name);
    }

    private List<UUID> categoryIds(UUID eventId, String category) {
        String base = "SELECT id FROM photos WHERE event_id = ? AND status = 'READY' AND NOT hidden";
        if (category == null || category.isBlank() || "all".equals(category)) {
            return jdbc.query(base, (rs, n) -> rs.getObject(1, UUID.class), eventId);
        }
        if ("none".equals(category)) {
            return jdbc.query(base + " AND category_id IS NULL", (rs, n) -> rs.getObject(1, UUID.class), eventId);
        }
        try {
            return jdbc.query(base + " AND category_id = ?", (rs, n) -> rs.getObject(1, UUID.class), eventId,
                    UUID.fromString(category));
        } catch (IllegalArgumentException bad) {
            throw ApiException.badRequest("INVALID_CATEGORY", "Unknown category");
        }
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {
    }

    public record PasswordChanged(String token, Instant expiresAt) {
    }

    /** Returns a fresh login for this device; other devices are logged out. */
    @PostMapping("/password")
    public PasswordChanged changePassword(Authentication auth, @RequestBody ChangePasswordRequest request) {
        ClientRow c = current(auth);
        if (request.currentPassword() == null || !passwordEncoder.matches(request.currentPassword(), c.passwordHash())) {
            throw ApiException.badRequest("WRONG_PASSWORD", "The current password is not correct");
        }
        AlbumService.password(request.newPassword());
        if (request.currentPassword().equals(request.newPassword())) {
            throw ApiException.badRequest("SAME_PASSWORD", "Choose a new password, different from the current one");
        }
        jdbc.update("""
                UPDATE event_clients SET password_hash = ?, must_change_password = false, token_version = token_version + 1
                WHERE id = ?""", passwordEncoder.encode(request.newPassword()), c.id());
        JwtService.IssuedToken token = jwt.issueClient(c.id(), c.eventId(), c.email(), c.tokenVersion() + 1);
        return new PasswordChanged(token.token(), token.expiresAt());
    }
}
