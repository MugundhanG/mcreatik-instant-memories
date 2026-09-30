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

    private final UploaderRepository uploaders;
    private final EventService events;
    private final GalleryProperties properties;

    public UploaderAdminService(UploaderRepository uploaders, EventService events, GalleryProperties properties) {
        this.uploaders = uploaders;
        this.events = events;
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
        String token = newToken();
        Uploader uploader = new Uploader(event.getId(), name.trim(), Tokens.sha256Hex(token), prefixOf(token));
        uploaders.save(uploader);
        return credentials(uploader, token);
    }

    @Transactional
    public UploaderCredentials rotateToken(UUID ownerId, UUID uploaderId) {
        Uploader uploader = owned(ownerId, uploaderId);
        String token = newToken();
        uploader.rotateToken(Tokens.sha256Hex(token), prefixOf(token));
        return credentials(uploader, token);
    }

    @Transactional
    public void rename(UUID ownerId, UUID uploaderId, String name) {
        owned(ownerId, uploaderId).rename(name.trim());
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
