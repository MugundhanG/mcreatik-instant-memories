package com.mcreatik.uploader.engine;

import java.util.List;

import com.mcreatik.uploader.queue.QueueCounts;

/** Immutable snapshot for the UI / console. */
public record EngineStatus(Connection connection, String blockedReason, String eventName, String uploaderName,
                           String galleryUrl, String watchFolder, QueueCounts counts, List<ActiveUpload> active,
                           String lastError) {

    public enum Connection { CONNECTING, ONLINE, OFFLINE, BLOCKED }

    public record ActiveUpload(String fileName, long bytesSent, long totalBytes) {
        public int percent() {
            return totalBytes <= 0 ? 0 : (int) Math.min(100, bytesSent * 100 / totalBytes);
        }
    }
}
