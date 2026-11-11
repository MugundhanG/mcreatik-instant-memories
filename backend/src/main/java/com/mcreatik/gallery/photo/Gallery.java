package com.mcreatik.gallery.photo;

import com.mcreatik.gallery.common.ApiException;
import com.mcreatik.gallery.event.Event;
import com.mcreatik.gallery.event.EventType;

/** Which of an event's two galleries a photo or category belongs to. */
public enum Gallery {
    /** Compressed photos guests see (QR gallery). */
    GUEST,
    /** Full-quality photos only the client sees. */
    CLIENT;

    /** Dashboard calls that omit the gallery: client-type events default to CLIENT, everything else to GUEST. */
    public static Gallery resolve(String param, Event event) {
        if (param == null || param.isBlank()) {
            return event.getEventType() == EventType.CLIENT ? CLIENT : GUEST;
        }
        try {
            return valueOf(param.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_GALLERY", "Gallery must be GUEST or CLIENT");
        }
    }
}
