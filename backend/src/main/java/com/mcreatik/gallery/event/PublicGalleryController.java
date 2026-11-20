package com.mcreatik.gallery.event;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.mcreatik.gallery.common.ApiException;
import com.mcreatik.gallery.photo.Cursor;
import com.mcreatik.gallery.photo.Photo;
import com.mcreatik.gallery.photo.PhotoDtos;
import com.mcreatik.gallery.photo.PhotoMapper;
import com.mcreatik.gallery.photo.PhotoRepository;
import com.mcreatik.gallery.realtime.GalleryBroadcaster;
import com.mcreatik.gallery.storage.StorageArea;
import com.mcreatik.gallery.storage.StorageService;

/** Guest-facing API. No login. Only READY derivatives are ever exposed. */
@RestController
@RequestMapping("/api/public/events/{slug}")
public class PublicGalleryController {

    private static final int DEFAULT_PAGE = 40;
    private static final int MAX_PAGE = 100;
    private static final int MAX_CATCH_UP = 200;

    private final EventService events;
    private final PhotoRepository photos;
    private final PhotoMapper mapper;
    private final GalleryBroadcaster broadcaster;

    private final com.mcreatik.gallery.album.AlbumService album;
    private final StorageService storage;
    private final com.mcreatik.gallery.face.FaceSearchService faces;

    public PublicGalleryController(EventService events, PhotoRepository photos, PhotoMapper mapper,
                                   GalleryBroadcaster broadcaster, com.mcreatik.gallery.album.AlbumService album,
                                   StorageService storage, com.mcreatik.gallery.face.FaceSearchService faces) {
        this.faces = faces;
        this.storage = storage;
        this.album = album;
        this.events = events;
        this.photos = photos;
        this.mapper = mapper;
        this.broadcaster = broadcaster;
    }

    @GetMapping
    public ResponseEntity<EventDtos.PublicEvent> event(@PathVariable String slug) {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(events.toPublic(events.publicEvent(slug)));
    }

    /**
     * Newest first. {@code before} pages backwards (infinite scroll); {@code after} returns everything
     * newer than the cursor, oldest first (catch-up after a reconnect).
     */
    @GetMapping("/photos")
    public ResponseEntity<PhotoDtos.PublicPhotoPage> photos(@PathVariable String slug,
                                                            @RequestParam(required = false) String before,
                                                            @RequestParam(required = false) String after,
                                                            @RequestParam(required = false) java.util.UUID category,
                                                            @RequestParam(defaultValue = "" + DEFAULT_PAGE) int limit) {
        if (before != null && after != null) {
            throw ApiException.badRequest("INVALID_QUERY", "Use either before or after, not both");
        }
        Event event = events.publicEvent(slug);
        requireOpen(event);
        List<Photo> page;
        String next = null;
        if (after != null) {
            Cursor c = Cursor.decode(after);
            int size = Math.clamp(limit, 1, MAX_CATCH_UP);
            page = photos.findReadyAfter(event.getId(), c.at(), c.id(), Limit.of(size));
        } else {
            int size = Math.clamp(limit, 1, MAX_PAGE);
            if (before != null) {
                Cursor c = Cursor.decode(before);
                page = category == null ? photos.findReadyBefore(event.getId(), c.at(), c.id(), Limit.of(size))
                        : photos.findReadyBeforeIn(event.getId(), category, c.at(), c.id(), Limit.of(size));
            } else {
                page = category == null ? photos.findLatestReady(event.getId(), Limit.of(size))
                        : photos.findLatestReadyIn(event.getId(), category, Limit.of(size));
            }
            if (page.size() == size) {
                next = new Cursor(page.getLast().getReadyAt(), page.getLast().getId()).encode();
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noCache())
                .body(new PhotoDtos.PublicPhotoPage(page.stream().map(mapper::toPublic).toList(), next));
    }

    /**
     * Download button fallback: the web-quality photo as an attachment, streamed from storage (never held in memory).
     * Works without CORS on the media host, and a plain link to it downloads even where scripts cannot save files.
     * Same visibility rules as the feed: this event's GUEST, READY, not hidden photo; anything else is 404.
     */
    @GetMapping("/photos/{photoId}/download")
    public ResponseEntity<org.springframework.core.io.InputStreamResource> download(@PathVariable String slug,
                                                                                    @PathVariable java.util.UUID photoId,
                                                                                    @RequestParam(required = false) String match) {
        Event event = events.publicEvent(slug);
        // A private gallery: only a photo of the guest's own match (no match, or not in it, looks like a missing photo).
        if (faces.isPrivate(event) && !faces.matchShows(event, match, photoId)) {
            throw ApiException.notFound("Photo");
        }
        Photo photo = photos.findByIdAndEventId(photoId, event.getId())
                .filter(p -> p.getGallery() == com.mcreatik.gallery.photo.Gallery.GUEST
                        && p.getStatus() == com.mcreatik.gallery.photo.PhotoStatus.READY && !p.isHidden()
                        && p.getOptimizedStorageKey() != null)
                .orElseThrow(() -> ApiException.notFound("Photo"));
        java.io.InputStream in = storage.open(StorageArea.MEDIA, photo.getOptimizedStorageKey())
                .orElseThrow(() -> ApiException.notFound("Photo"));
        String fileName = event.getSlug() + "-" + photo.getId().toString().substring(0, 8) + ".jpg";
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.IMAGE_JPEG)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment().filename(fileName).build().toString())
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(10)).cachePrivate())
                .body(new org.springframework.core.io.InputStreamResource(in));
    }

    /** The studio's photo categories that have photos guests can see, for the gallery's category tabs. */
    @GetMapping("/categories")
    public ResponseEntity<List<com.mcreatik.gallery.album.AlbumDtos.Category>> categories(@PathVariable String slug) {
        Event event = events.publicEvent(slug);
        if (faces.isPrivate(event)) {
            return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(List.of());
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noCache())
                .body(album.visibleCategories(event.getId(), com.mcreatik.gallery.photo.Gallery.GUEST).stream().filter(c -> c.photos() > 0).toList());
    }

    @GetMapping(path = "/stream", produces = "text/event-stream")
    public ResponseEntity<SseEmitter> stream(@PathVariable String slug) {
        Event event = events.publicEvent(slug);
        requireOpen(event); // a private gallery's guests get their new photos through their own match instead
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(broadcaster.subscribe(event.getId()));
    }

    /** A private gallery never lists all its photos: guests see only the ones their selfie found. */
    private void requireOpen(Event event) {
        if (faces.isPrivate(event)) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "PRIVATE_GALLERY",
                    "This gallery is private: take a selfie to see the photos you are in");
        }
    }
}
