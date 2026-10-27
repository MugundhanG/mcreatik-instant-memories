package com.mcreatik.gallery.photo;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final AdminPhotoSearch search;

    public AdminPhotoController(PhotoRepository photos, EventService events, PhotoMapper mapper,
                                StorageService storage, GalleryBroadcaster broadcaster, AdminPhotoSearch search) {
        this.search = search;
        this.photos = photos;
        this.events = events;
        this.mapper = mapper;
        this.storage = storage;
        this.broadcaster = broadcaster;
    }

    @GetMapping("/events/{eventId}/photos")
    @Transactional(readOnly = true)
    public PhotoDtos.AdminPhotoPage list(Authentication auth, @PathVariable UUID eventId,
                                         @RequestParam(defaultValue = "NEWEST") AdminPhotoSearch.Sort sort,
                                         @RequestParam(required = false) UUID uploaderId,
                                         @RequestParam(required = false) Boolean liked,
                                         @RequestParam(required = false) Boolean hidden,
                                         @RequestParam(required = false) LocalDate day,
                                         @RequestParam(defaultValue = "0") int offset,
                                         @RequestParam(defaultValue = "60") int limit) {
        events.owned(CurrentAdmin.id(auth), eventId);
        int size = Math.clamp(limit, 1, 200);
        int from = Math.max(0, offset);
        // One extra row tells whether another page exists.
        List<Photo> rows = search.find(eventId, new AdminPhotoSearch.Filter(uploaderId, liked, hidden, day), sort,
                from, size + 1);
        boolean more = rows.size() > size;
        List<Photo> page = more ? rows.subList(0, size) : rows;
        return new PhotoDtos.AdminPhotoPage(page.stream().map(mapper::toAdmin).toList(), more ? from + size : null);
    }

    /** Counts for the dashboard's filters: days with photos, liked and hidden totals. */
    @GetMapping("/events/{eventId}/photo-facets")
    @Transactional(readOnly = true)
    public PhotoDtos.PhotoFacets facets(Authentication auth, @PathVariable UUID eventId) {
        events.owned(CurrentAdmin.id(auth), eventId);
        Object[] counts = photos.countHiddenAndLiked(eventId).getFirst();
        return new PhotoDtos.PhotoFacets(search.days(eventId), ((Number) counts[1]).longValue(),
                ((Number) counts[0]).longValue());
    }

    /**
     * Like / unlike, hide / show. Hiding takes the photo off guests' screens straight away (and stops it being the
     * cover); showing it again puts it back.
     */
    @PatchMapping("/photos/{photoId}")
    @Transactional
    public PhotoDtos.AdminPhoto update(Authentication auth, @PathVariable UUID photoId,
                                       @RequestBody PhotoDtos.UpdatePhotoRequest request) {
        Photo photo = owned(auth, photoId);
        if (request.liked() != null) {
            photo.setLiked(request.liked());
        }
        if (request.hidden() != null && request.hidden() != photo.isHidden()) {
            photo.setHidden(request.hidden());
            if (photo.isHidden()) {
                events.clearCoverIf(photo.getEventId(), photo.getId());
            }
            if (photo.getStatus() == PhotoStatus.READY) {
                if (photo.isHidden()) {
                    broadcaster.photoRemoved(photo.getEventId(), photo.getId());
                } else {
                    broadcaster.photoReady(photo.getEventId(), mapper.toPublic(photo));
                }
            }
        }
        return mapper.toAdmin(photo);
    }

    public record OriginalUrl(String url) {
    }

    @GetMapping("/photos/{photoId}/original")
    public OriginalUrl original(Authentication auth, @PathVariable UUID photoId) {
        Photo photo = owned(auth, photoId);
        if (!photo.isOriginalStored()) {
            throw ApiException.conflict("ORIGINAL_NOT_UPLOADED",
                    "The original was not uploaded: it stays on the photographer's laptop and memory card");
        }
        return new OriginalUrl(storage.presignDownload(StorageArea.ORIGINALS, photo.getStorageKey(),
                Duration.ofMinutes(5), photo.getOriginalFileName()));
    }

    /** Removes the photo everywhere, including from guests' open galleries. */
    @DeleteMapping("/photos/{photoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth, @PathVariable UUID photoId) {
        Photo photo = owned(auth, photoId);
        if (photo.isOriginalStored()) {
            storage.delete(StorageArea.ORIGINALS, photo.getStorageKey());
        }
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
