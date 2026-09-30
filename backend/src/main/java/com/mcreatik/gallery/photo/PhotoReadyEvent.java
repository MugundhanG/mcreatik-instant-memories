package com.mcreatik.gallery.photo;

import java.util.UUID;

/**
 * Published after a photo became READY and was committed. Listeners: the live gallery broadcaster today;
 * future AI features (face indexing) subscribe here without touching the upload/processing core.
 */
public record PhotoReadyEvent(UUID eventId, UUID photoId) {
}
