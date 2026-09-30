package com.mcreatik.uploader.queue;

import java.nio.file.Path;

public record QueueItem(long id, Path path, String fileName, long size, long modifiedMillis, String sha256,
                        String mimeType, QueueStatus status, int attempts, long nextAttemptAt, String photoId,
                        String lastError, long createdAt, long updatedAt) {
}
