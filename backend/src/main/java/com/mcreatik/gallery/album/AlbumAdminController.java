package com.mcreatik.gallery.album;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mcreatik.gallery.auth.CurrentAdmin;
import com.mcreatik.gallery.download.ZipDownloads;
import com.mcreatik.gallery.event.Event;
import com.mcreatik.gallery.event.EventService;
import com.mcreatik.gallery.photo.Gallery;

/** The studio's side: categories, sorting photos into them, client logins and the couple's album selection. */
@RestController
@RequestMapping("/api/admin/events/{eventId}")
public class AlbumAdminController {

    private final AlbumService album;
    private final EventService events;
    private final ZipDownloads downloads;

    public AlbumAdminController(AlbumService album, EventService events, ZipDownloads downloads) {
        this.album = album;
        this.events = events;
        this.downloads = downloads;
    }

    private Event owned(Authentication auth, UUID eventId) {
        return events.owned(CurrentAdmin.id(auth), eventId);
    }

    @GetMapping("/categories")
    public List<AlbumDtos.Category> categories(Authentication auth, @PathVariable UUID eventId,
                                               @RequestParam(required = false) String gallery) {
        Event event = owned(auth, eventId);
        return album.categories(eventId, Gallery.resolve(gallery, event));
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public AlbumDtos.Category createCategory(Authentication auth, @PathVariable UUID eventId,
                                             @RequestBody AlbumDtos.CategoryRequest request) {
        Event event = owned(auth, eventId);
        return album.createCategory(eventId, request.name(), Gallery.resolve(request.gallery(), event));
    }

    @PatchMapping("/categories/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void renameCategory(Authentication auth, @PathVariable UUID eventId, @PathVariable UUID categoryId,
                               @RequestBody AlbumDtos.CategoryRequest request) {
        owned(auth, eventId);
        album.renameCategory(eventId, categoryId, request.name());
    }

    @DeleteMapping("/categories/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(Authentication auth, @PathVariable UUID eventId, @PathVariable UUID categoryId) {
        owned(auth, eventId);
        album.deleteCategory(eventId, categoryId);
    }

    @PostMapping("/photos/category")
    public Map<String, Integer> assign(Authentication auth, @PathVariable UUID eventId,
                                       @RequestBody AlbumDtos.AssignRequest request) {
        owned(auth, eventId);
        return Map.of("moved", album.assign(eventId, request.photoIds(), request.categoryId()));
    }

    @GetMapping("/clients")
    public List<AlbumDtos.Client> clients(Authentication auth, @PathVariable UUID eventId) {
        owned(auth, eventId);
        return album.clients(eventId);
    }

    @PostMapping("/clients")
    @ResponseStatus(HttpStatus.CREATED)
    public AlbumDtos.Client createClient(Authentication auth, @PathVariable UUID eventId,
                                         @RequestBody AlbumDtos.CreateClientRequest request) {
        owned(auth, eventId);
        return album.createClient(eventId, request);
    }

    @PatchMapping("/clients/{clientId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateClient(Authentication auth, @PathVariable UUID eventId, @PathVariable UUID clientId,
                             @RequestBody AlbumDtos.UpdateClientRequest request) {
        owned(auth, eventId);
        album.updateClient(eventId, clientId, request);
    }

    @DeleteMapping("/clients/{clientId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteClient(Authentication auth, @PathVariable UUID eventId, @PathVariable UUID clientId) {
        owned(auth, eventId);
        album.deleteClient(eventId, clientId);
    }

    @GetMapping("/album")
    public AlbumDtos.Album selection(Authentication auth, @PathVariable UUID eventId) {
        owned(auth, eventId);
        return album.album(eventId);
    }

    @PutMapping("/album")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setLimit(Authentication auth, @PathVariable UUID eventId, @RequestBody AlbumDtos.AlbumLimitRequest request) {
        owned(auth, eventId);
        album.setAlbumLimit(eventId, request.limit());
    }

    /** Selected photos (or the couple's whole album selection) as one ZIP, originals where kept. */
    @PostMapping("/downloads")
    public ZipDownloads.Prepared download(Authentication auth, @PathVariable UUID eventId,
                                          @RequestBody AlbumDtos.DownloadRequest request) {
        Event event = owned(auth, eventId);
        boolean wholeAlbum = Boolean.TRUE.equals(request.album());
        List<UUID> ids = wholeAlbum ? album.selectedPhotoIds(eventId) : request.photoIds();
        return downloads.prepare(eventId, ids, true, event.getName() + (wholeAlbum ? " album selection" : " photos"));
    }
}
