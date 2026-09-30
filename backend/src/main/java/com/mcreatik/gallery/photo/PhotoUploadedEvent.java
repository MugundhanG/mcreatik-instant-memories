package com.mcreatik.gallery.photo;

import java.util.UUID;

/** Published when an original has landed in storage; wakes the processing workers after commit. */
public record PhotoUploadedEvent(UUID photoId) {
}
