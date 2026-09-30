package com.mcreatik.gallery.photo;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mcreatik.gallery.auth.CurrentAdmin;
import com.mcreatik.gallery.common.ApiException;
import com.mcreatik.gallery.event.EventService;
import com.mcreatik.gallery.realtime.GalleryBroadcaster;
import com.mcreatik.gallery.storage.StorageArea;
import com.mcreatik.gallery.storage.StorageService;

@RestController
@RequestMapping("/api/admin")
public class AdminPhotoController {

    private final PhotoRepository photos;
    private final EventService events;
    private final PhotoMapper mapper;
    private final StorageService storage;
    private final GalleryBroadcaster broadcaster;

    public AdminPhotoController(PhotoRepository photos, EventService events, PhotoMapper mapper,
                                StorageService storage, GalleryBroadcaster broadcaster) {
        this.photos = photos;
        this.events = events;
        this.mapper = mapper;
        this.storage = storage;
        this.broadcaster = broadcaster;
    }

    @GetMapping("/events/{eventId}/photos")
    @Transactional(readOnly = true)
    public PhotoDtos.AdminPhotoPage list(Authentication auth, @PathVariable UUID eventId,
                                         @RequestParam(required = false) String before,
                                         @RequestParam(defaultValue = "60") int limit) {
        events.owned(CurrentAdmin.id(auth), eventId);
        int size = Math.clamp(limit, 1, 200);
        List<Photo> page;
        if (before == null) {
            page = photos.findForAdmin(eventId, Limit.of(size));
        } else {
            Cursor c = Cursor.decode(before);
            page = photos.findForAdminBefore(eventId, c.at(), c.id(), Limit.of(size));
        }
        String next = page.size() == size
                ? new Cursor(page.getLast().getCreatedAt(), page.getLast().getId()).encode() : null;
        return new PhotoDtos.AdminPhotoPage(page.stream().map(mapper::toAdmin).toList(), next);
    }

    public record OriginalUrl(String url) {
    }

    @GetMapping("/photos/{photoId}/original")
    public OriginalUrl original(Authentication auth, @PathVariable UUID photoId) {
        Photo photo = owned(auth, photoId);
        return new OriginalUrl(storage.presignDownload(StorageArea.ORIGINALS, photo.getStorageKey(),
                Duration.ofMinutes(5), photo.getOriginalFileName()));
    }

    /** Removes the photo everywhere, including from guests' open galleries. */
    @DeleteMapping("/photos/{photoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth, @PathVariable UUID photoId) {
        Photo photo = owned(auth, photoId);
        storage.delete(StorageArea.ORIGINALS, photo.getStorageKey());
        if (photo.getOptimizedStorageKey() != null) {
            storage.delete(StorageArea.MEDIA, photo.getOptimizedStorageKey());
        }
        if (photo.getThumbnailStorageKey() != null) {
            storage.delete(StorageArea.MEDIA, photo.getThumbnailStorageKey());
        }
        photos.delete(photo);
        broadcaster.photoRemoved(photo.getEventId(), photo.getId());
    }

    private Photo owned(Authentication auth, UUID photoId) {
        Photo photo = photos.findById(photoId).orElseThrow(() -> ApiException.notFound("Photo"));
        events.owned(CurrentAdmin.id(auth), photo.getEventId());
        return photo;
    }
}
