package com.mcreatik.gallery.uploader;

import java.util.UUID;

/** The authenticated identity of an uploader app. Scoped to exactly one event. */
public record UploaderPrincipal(UUID uploaderId, UUID eventId) {
}
