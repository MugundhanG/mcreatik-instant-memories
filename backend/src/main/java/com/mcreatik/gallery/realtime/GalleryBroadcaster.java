package com.mcreatik.gallery.realtime;

import java.util.UUID;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.mcreatik.gallery.event.EventStatus;
import com.mcreatik.gallery.photo.PhotoDtos;

/**
 * Pushes live gallery updates to connected guests. V1 is in-memory (single backend instance); a
 * multi-instance deployment swaps this for a Redis / Postgres LISTEN-NOTIFY backed implementation.
 */
public interface GalleryBroadcaster {

    SseEmitter subscribe(UUID eventId);

    void photoReady(UUID eventId, PhotoDtos.PublicPhoto photo);

    void photoRemoved(UUID eventId, UUID photoId);

    void eventStatusChanged(UUID eventId, EventStatus status);

    int subscriberCount(UUID eventId);
}
