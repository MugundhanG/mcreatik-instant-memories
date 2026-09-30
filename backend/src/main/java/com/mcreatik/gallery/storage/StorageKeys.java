package com.mcreatik.gallery.storage;

import java.util.UUID;

/** All objects of an event share the prefix events/{eventId}/ so an event can be deleted with one prefix delete. */
public final class StorageKeys {

    private StorageKeys() {
    }

    public static String eventPrefix(UUID eventId) {
        return "events/" + eventId + "/";
    }

    public static String original(UUID eventId, UUID photoId, String mimeType) {
        String ext = "image/png".equals(mimeType) ? "png" : "jpg";
        return eventPrefix(eventId) + "originals/" + photoId + "." + ext;
    }

    public static String optimized(UUID eventId, UUID photoId) {
        return eventPrefix(eventId) + "web/" + photoId + ".jpg";
    }

    public static String thumbnail(UUID eventId, UUID photoId) {
        return eventPrefix(eventId) + "thumb/" + photoId + ".jpg";
    }
}
