package com.mcreatik.gallery.photo;

/**
 * Photo lifecycle. QUEUED lives on the uploader (local queue, not yet announced to the server);
 * the server tracks UPLOADING → UPLOADED → PROCESSING → READY | FAILED.
 */
public enum PhotoStatus {
    QUEUED, UPLOADING, UPLOADED, PROCESSING, READY, FAILED
}
