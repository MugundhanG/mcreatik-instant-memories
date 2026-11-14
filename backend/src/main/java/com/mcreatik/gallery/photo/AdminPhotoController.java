package com.mcreatik.gallery.photo;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.mcreatik.gallery.album.AlbumService;
import com.mcreatik.gallery.auth.CurrentAdmin;
import com.mcreatik.gallery.common.ApiException;
import com.mcreatik.gallery.event.EventService;
import com.mcreatik.gallery.face.FaceSearchService;
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
    private final UploadService uploads;
    private final AlbumService album;
    private final FaceSearchService faces;

    public AdminPhotoController(PhotoRepository photos, EventService events, PhotoMapper mapper,
                                StorageService storage, GalleryBroadcaster broadcaster, AdminPhotoSearch search,
                                UploadService uploads, AlbumService album, FaceSearchService faces) {
        this.faces = faces;
        this.search = search;
        this.uploads = uploads;
        this.album = album;
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
                                         @RequestParam(required = false) String gallery,
                                         @RequestParam(required = false) UUID uploaderId,
                                         @RequestParam(required = false) Boolean liked,
                                         @RequestParam(required = false) Boolean hidden,
                                         @RequestParam(required = false) LocalDate day,
                                         @RequestParam(required = false) String category,
                                         @RequestParam(required = false) Boolean selected,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(defaultValue = "0") int offset,
                                         @RequestParam(defaultValue = "60") int limit) {
        Gallery which = Gallery.resolve(gallery, events.owned(CurrentAdmin.id(auth), eventId));
        int size = Math.clamp(limit, 1, 200);
        int from = Math.max(0, offset);
        // One extra row tells whether another page exists.
        if (category != null && !category.isBlank() && !AdminPhotoSearch.UNSORTED.equals(category)) {
            try {
                UUID.fromString(category);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("INVALID_CATEGORY", "Unknown category");
            }
        }
        List<Photo> rows = search.find(eventId, which,
                new AdminPhotoSearch.Filter(uploaderId, liked, hidden, day, category, selected,
                        q == null ? null : q.substring(0, Math.min(q.length(), 100))), sort, from, size + 1);
        boolean more = rows.size() > size;
        List<Photo> page = more ? rows.subList(0, size) : rows;
        List<UUID> chosen = album.selectedIds(eventId, page.stream().map(Photo::getId).toList());
        return new PhotoDtos.AdminPhotoPage(page.stream().map(p -> mapper.toAdmin(p, chosen.contains(p.getId()))).toList(),
                more ? from + size : null);
    }

    /**
     * Manual upload from the dashboard. The browser sends the gallery (web) and thumbnail JPEGs it made from the
     * photo plus the original's details; the original itself stays with the photographer.
     */
    @PostMapping(value = "/events/{eventId}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadService.AdminUploadResponse upload(Authentication auth, @PathVariable UUID eventId,
                                                    @RequestPart("web") MultipartFile web,
                                                    @RequestPart("thumbnail") MultipartFile thumbnail,
                                                    @RequestParam String fileName, @RequestParam long fileSize,
                                                    @RequestParam String mimeType, @RequestParam String checksumSha256,
                                                    @RequestParam int width, @RequestParam int height,
                                                    @RequestParam(required = false) Instant capturedAt,
                                                    @RequestParam(defaultValue = "false") boolean original,
                                                    @RequestParam(required = false) UUID categoryId,
                                                    @RequestParam(required = false) String gallery)
            throws IOException {
        // Older callers omit the gallery: an original means the client gallery, otherwise the event's default.
        Gallery which = original && (gallery == null || gallery.isBlank()) ? Gallery.CLIENT
                : Gallery.resolve(gallery, events.owned(CurrentAdmin.id(auth), eventId));
        events.owned(CurrentAdmin.id(auth), eventId);
        album.requireCategory(eventId, categoryId, which);
        var res = uploads.adminUpload(eventId, which, new UploadService.AdminUpload(fileName, fileSize, mimeType,
                checksumSha256, width, height, capturedAt), web.getBytes(), thumbnail.getBytes(), original);
        intoCategory(eventId, res.photoId(), categoryId);
        return res;
    }

    /** Uploading "into" a category (e.g. the Cake cutting folder) files the photo there, new or already present. */
    private void intoCategory(UUID eventId, UUID photoId, UUID categoryId) {
        if (categoryId != null) {
            album.assign(eventId, List.of(photoId), categoryId);
        }
    }

    /** {@code gallery}: GUEST or CLIENT; an original always goes to the client gallery (the default here). */
    public record OriginalUploadRequest(String fileName, long fileSize, String mimeType, String checksumSha256,
                                        UUID categoryId, String gallery) {
    }

    /**
     * "Original quality" upload from the dashboard: the browser PUTs the full-quality file straight to storage
     * (never through this server), then sends the gallery versions (or, for a photo already in the gallery, just
     * confirms with {@code /original}).
     */
    @PostMapping("/events/{eventId}/original-uploads")
    public UploadService.OriginalSession originalUpload(Authentication auth, @PathVariable UUID eventId,
                                                        @RequestBody OriginalUploadRequest request) {
        events.owned(CurrentAdmin.id(auth), eventId);
        // Originals only ever go to the client gallery; asking for GUEST is refused by the service.
        Gallery which = request.gallery() == null || request.gallery().isBlank() ? Gallery.CLIENT
                : Gallery.resolve(request.gallery(), null);
        album.requireCategory(eventId, request.categoryId(), which);
        var session = uploads.adminOriginalSession(eventId, which, request.fileName(), request.fileSize(), request.mimeType(),
                request.checksumSha256());
        if (session.outcome() == UploadService.SessionOutcome.DUPLICATE || !session.needsVersions()) {
            intoCategory(eventId, session.photoId(), request.categoryId());
        }
        return session;
    }

    @PostMapping("/events/{eventId}/photos/{photoId}/original")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void attachOriginal(Authentication auth, @PathVariable UUID eventId, @PathVariable UUID photoId) {
        events.owned(CurrentAdmin.id(auth), eventId);
        uploads.attachOriginal(eventId, photoId);
    }

    /** Counts for the dashboard's filters: days with photos, liked and hidden totals. */
    @GetMapping("/events/{eventId}/photo-facets")
    @Transactional(readOnly = true)
    public PhotoDtos.PhotoFacets facets(Authentication auth, @PathVariable UUID eventId,
                                        @RequestParam(required = false) String gallery) {
        Gallery which = Gallery.resolve(gallery, events.owned(CurrentAdmin.id(auth), eventId));
        Object[] counts = photos.countHiddenAndLikedIn(eventId, which.name()).getFirst();
        return new PhotoDtos.PhotoFacets(search.days(eventId, which), ((Number) counts[1]).longValue(),
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
        if (request.hidden() != null) {
            applyHidden(photo, request.hidden());
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
        Runnable removeFaces = faces.captureFaces(photo.getEventId(), List.of(photo.getId()));
        photos.delete(photo);
        broadcaster.photoRemoved(photo.getEventId(), photo.getId());
        deleteFiles(List.of(photo));
        removeFaces.run();
    }

    public enum BulkAction { LIKE, UNLIKE, HIDE, SHOW, DELETE }

    public record BulkRequest(List<UUID> photoIds, BulkAction action) {
    }

    public record BulkResult(int changed) {
    }

    /**
     * Like, hide or delete many photos at once (the dashboard's Select mode). Only photos of this event are touched;
     * guests' open galleries update as with single changes.
     */
    @PostMapping("/events/{eventId}/photos/bulk")
    @Transactional
    public BulkResult bulk(Authentication auth, @PathVariable UUID eventId, @RequestBody BulkRequest request) {
        events.owned(CurrentAdmin.id(auth), eventId);
        if (request.action() == null || request.photoIds() == null || request.photoIds().isEmpty()) {
            throw ApiException.badRequest("INVALID_REQUEST", "Choose some photos and an action");
        }
        if (request.photoIds().size() > MAX_BULK) {
            throw ApiException.badRequest("TOO_MANY", "Change at most 1,000 photos at a time");
        }
        List<Photo> found = photos.findAllById(request.photoIds()).stream()
                .filter(p -> p.getEventId().equals(eventId)).toList();
        switch (request.action()) {
            case LIKE, UNLIKE -> found.forEach(p -> p.setLiked(request.action() == BulkAction.LIKE));
            case HIDE, SHOW -> found.forEach(p -> applyHidden(p, request.action() == BulkAction.HIDE));
            case DELETE -> {
                Runnable removeFaces = faces.captureFaces(eventId, found.stream().map(Photo::getId).toList());
                photos.deleteAll(found);
                // Guests and storage follow only once the rows are really gone.
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        found.forEach(p -> broadcaster.photoRemoved(p.getEventId(), p.getId()));
                        deleteFiles(found);
                        removeFaces.run();
                    }
                });
            }
        }
        return new BulkResult(found.size());
    }

    static final int MAX_BULK = 1000;

    /** Hiding takes the photo off guests' screens straight away (and stops it being the cover); showing puts it back. */
    private void applyHidden(Photo photo, boolean hidden) {
        if (hidden == photo.isHidden()) {
            return;
        }
        photo.setHidden(hidden);
        if (hidden) {
            events.clearCoverIf(photo.getEventId(), photo.getId());
        }
        if (photo.getStatus() == PhotoStatus.READY && photo.getGallery() == Gallery.GUEST) {
            if (hidden) {
                broadcaster.photoRemoved(photo.getEventId(), photo.getId());
            } else {
                broadcaster.photoReady(photo.getEventId(), mapper.toPublic(photo));
            }
        }
    }

    /** The originals and gallery copies of deleted photos, removed in batches. */
    private void deleteFiles(List<Photo> deleted) {
        storage.deleteAll(StorageArea.ORIGINALS, deleted.stream().filter(Photo::isOriginalStored).map(Photo::getStorageKey).toList());
        storage.deleteAll(StorageArea.MEDIA, deleted.stream()
                .flatMap(p -> java.util.stream.Stream.of(p.getOptimizedStorageKey(), p.getThumbnailStorageKey()))
                .filter(java.util.Objects::nonNull).toList());
    }

    private Photo owned(Authentication auth, UUID photoId) {
        Photo photo = photos.findById(photoId).orElseThrow(() -> ApiException.notFound("Photo"));
        events.owned(CurrentAdmin.id(auth), photo.getEventId());
        return photo;
    }
}
