package com.mcreatik.uploader.queue;

public enum QueueStatus {
    /** Waiting (possibly until next_attempt_at, after a network error). */
    QUEUED,
    /** A worker is sending it right now. Reset to QUEUED if the app restarts mid-upload. */
    UPLOADING,
    /** Accepted by McreatiK. */
    DONE,
    /** McreatiK already had this exact photo for the event. */
    DUPLICATE,
    /** Permanent problem (file deleted, rejected type/size). Retried only when the photographer asks. */
    FAILED
}
