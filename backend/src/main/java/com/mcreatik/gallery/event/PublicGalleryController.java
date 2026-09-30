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

    public PublicGalleryController(EventService events, PhotoRepository photos, PhotoMapper mapper,
                                   GalleryBroadcaster broadcaster) {
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
                                                            @RequestParam(defaultValue = "" + DEFAULT_PAGE) int limit) {
        if (before != null && after != null) {
            throw ApiException.badRequest("INVALID_QUERY", "Use either before or after, not both");
        }
        Event event = events.publicEvent(slug);
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
                page = photos.findReadyBefore(event.getId(), c.at(), c.id(), Limit.of(size));
            } else {
                page = photos.findLatestReady(event.getId(), Limit.of(size));
            }
            if (page.size() == size) {
                next = new Cursor(page.getLast().getReadyAt(), page.getLast().getId()).encode();
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noCache())
                .body(new PhotoDtos.PublicPhotoPage(page.stream().map(mapper::toPublic).toList(), next));
    }

    @GetMapping(path = "/stream", produces = "text/event-stream")
    public ResponseEntity<SseEmitter> stream(@PathVariable String slug) {
        Event event = events.publicEvent(slug);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(broadcaster.subscribe(event.getId()));
    }
}
