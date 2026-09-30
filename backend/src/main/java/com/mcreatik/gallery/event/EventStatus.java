package com.mcreatik.gallery.event;

public enum EventStatus {
    DRAFT, UPCOMING, LIVE, COMPLETED, ARCHIVED;

    /** Guests can open the gallery. DRAFT is private set-up, ARCHIVED is closed. */
    public boolean isPubliclyVisible() {
        return this == UPCOMING || this == LIVE || this == COMPLETED;
    }

    /** Uploaders can send photos. Anything but ARCHIVED, so a forgotten status switch never loses photos. */
    public boolean acceptsUploads() {
        return this != ARCHIVED;
    }
}
