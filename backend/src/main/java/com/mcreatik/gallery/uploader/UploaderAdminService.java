package com.mcreatik.gallery.uploader;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mcreatik.gallery.common.ApiException;
import com.mcreatik.gallery.common.Tokens;
import com.mcreatik.gallery.config.GalleryProperties;
import com.mcreatik.gallery.event.Event;
import com.mcreatik.gallery.event.EventService;

@Service
public class UploaderAdminService {

    /** Cameras one event can have when the uploader app adds them itself ("Add camera"). */
    public static final int MAX_UPLOADERS_PER_EVENT = 8;

    private final UploaderRepository uploaders;
    private final EventService events;
    private final com.mcreatik.gallery.event.EventRepository eventRepository;
    private final GalleryProperties properties;

    public UploaderAdminService(UploaderRepository uploaders, EventService events,
                                com.mcreatik.gallery.event.EventRepository eventRepository, GalleryProperties properties) {
        this.uploaders = uploaders;
        this.events = events;
        this.eventRepository = eventRepository;
        this.properties = properties;
    }

    /**
     * Returned only once, on creation or rotation. The plaintext token is never stored.
     * The connection code bundles server URL + token so a photographer pastes a single string.
     */
    public record UploaderCredentials(UUID uploaderId, String name, String token, String connectionCode) {
    }

    @Transactional
    public UploaderCredentials create(UUID ownerId, UUID eventId, String name) {
        Event event = events.owned(ownerId, eventId);
        String clean = cleanName(name);
        if (clean == null) {
            throw ApiException.badRequest("INVALID_NAME", "Enter a camera name");
        }
        if (uploaders.findByEventIdOrderByCreatedAtAsc(event.getId()).stream()
                .anyMatch(u -> u.getName().trim().equalsIgnoreCase(clean))) {
            throw nameTaken(clean);
        }
        String token = newToken();
        Uploader uploader = new Uploader(event.getId(), clean, Tokens.sha256Hex(token), prefixOf(token));
        uploaders.save(uploader);
        return credentials(uploader, token);
    }

    /** Longest camera name (the dashboard and the app both limit it). */
    public static final int MAX_NAME = 60;

    /**
     * "Add camera" in the uploader app: a new uploader in the caller's own event, with the name the photographer typed
     * or, when blank, the next free "Camera N". Only the event of an uploader that is still valid; never another event.
     */
    @Transactional
    public UploaderCredentials createSibling(UploaderPrincipal caller, String requestedName) {
        Event event = eventRepository.findById(caller.eventId()).orElseThrow(() -> ApiException.notFound("Event"));
        if (event.getStatus() == com.mcreatik.gallery.event.EventStatus.ARCHIVED) {
            throw ApiException.conflict("EVENT_ARCHIVED", "This event is archived. Cameras can't be added to it.");
        }
        java.util.List<Uploader> existing = uploaders.findByEventIdOrderByCreatedAtAsc(event.getId());
        if (existing.size() >= MAX_UPLOADERS_PER_EVENT) {
            throw ApiException.conflict("TOO_MANY_CAMERAS",
                    "This event already has " + MAX_UPLOADERS_PER_EVENT + " cameras. Remove one in the dashboard first.");
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        existing.forEach(u -> names.add(u.getName().trim().toLowerCase(java.util.Locale.ROOT)));
        int n = existing.size() + 1;
        while (names.contains(("camera " + n))) {
            n++;
        }
        String name = cleanName(requestedName);
        if (name == null) {
            name = "Camera " + n;
        } else if (names.contains(name.toLowerCase(java.util.Locale.ROOT))) {
            throw nameTaken(name);
        }
        String token = newToken();
        Uploader uploader = new Uploader(event.getId(), name, Tokens.sha256Hex(token), prefixOf(token));
        uploaders.save(uploader);
        return credentials(uploader, token);
    }

    /**
     * Rename and/or pause or resume a camera from the dashboard (either field may be null: unchanged).
     * Names are unique within an event so the photographer's folders and screens never mix two cameras up.
     */
    @Transactional
    public void update(UUID ownerId, UUID uploaderId, String name, Boolean paused) {
        apply(owned(ownerId, uploaderId), name, paused);
    }

    /** The same from the uploader app, for the camera making the call. */
    @Transactional
    public Uploader updateSelf(UploaderPrincipal caller, String name, Boolean paused) {
        Uploader uploader = uploaders.findById(caller.uploaderId()).orElseThrow(() -> ApiException.notFound("Uploader"));
        apply(uploader, name, paused);
        return uploader;
    }

    private void apply(Uploader uploader, String name, Boolean paused) {
        if (name != null) {
            String clean = cleanName(name);
            if (clean == null) {
                throw ApiException.badRequest("INVALID_NAME", "Enter a camera name");
            }
            boolean taken = uploaders.findByEventIdOrderByCreatedAtAsc(uploader.getEventId()).stream()
                    .anyMatch(u -> !u.getId().equals(uploader.getId()) && u.getName().trim().equalsIgnoreCase(clean));
            if (taken) {
                throw nameTaken(clean);
            }
            uploader.rename(clean);
        }
        if (paused != null) {
            uploader.setPaused(paused);
        }
    }

    /** Trimmed, single-spaced, at most {@link #MAX_NAME} characters; null when blank. */
    static String cleanName(String name) {
        if (name == null) {
            return null;
        }
        String s = name.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.length() > MAX_NAME) {
            throw ApiException.badRequest("INVALID_NAME", "Camera names can be up to " + MAX_NAME + " characters");
        }
        return s;
    }

    private static ApiException nameTaken(String name) {
        return ApiException.conflict("CAMERA_NAME_TAKEN", "Another camera in this event is already called \u201c" + name
                + "\u201d. Choose a different name.");
    }

    /** "Remove camera" in the uploader app: the uploader deletes itself (its photos stay in the gallery). */
    @Transactional
    public void deleteSelf(UploaderPrincipal caller) {
        uploaders.findById(caller.uploaderId()).ifPresent(uploaders::delete);
    }

    @Transactional
    public UploaderCredentials rotateToken(UUID ownerId, UUID uploaderId) {
        Uploader uploader = owned(ownerId, uploaderId);
        String token = newToken();
        uploader.rotateToken(Tokens.sha256Hex(token), prefixOf(token));
        return credentials(uploader, token);
    }

    @Transactional
    public void delete(UUID ownerId, UUID uploaderId) {
        uploaders.delete(owned(ownerId, uploaderId));
    }

    private Uploader owned(UUID ownerId, UUID uploaderId) {
        Uploader uploader = uploaders.findById(uploaderId).orElseThrow(() -> ApiException.notFound("Uploader"));
        events.owned(ownerId, uploader.getEventId()); // throws 404 if the event is not the caller's
        return uploader;
    }

    private UploaderCredentials credentials(Uploader uploader, String token) {
        String json = "{\"server\":\"" + GalleryProperties.stripTrailingSlash(properties.apiPublicBaseUrl())
                + "\",\"token\":\"" + token + "\"}";
        String code = "MCK1." + Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return new UploaderCredentials(uploader.getId(), uploader.getName(), token, code);
    }

    private static String newToken() {
        return Tokens.randomToken(UploaderTokenAuthenticationFilter.TOKEN_PREFIX);
    }

    private static String prefixOf(String token) {
        return token.substring(0, 10);
    }
}
