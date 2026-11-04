package com.mcreatik.uploader.engine;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.mcreatik.uploader.queue.QueueCounts;

/**
 * Immutable snapshot for the UI / console.
 *
 * @param onlineSince    when McreatiK was last reached after being offline (null while not online)
 * @param offlineSince   when the connection was lost (null while not offline)
 * @param bytesPerSecond upload speed over the last 30 seconds, 0 when nothing finished in that time
 * @param eventDate      the event's date from the dashboard, null when the server doesn't send it yet
 */
public record EngineStatus(Connection connection, String blockedReason, String eventName, String uploaderName,
                           String galleryUrl, String watchFolder, QueueCounts counts, List<ActiveUpload> active,
                           String lastError, Instant onlineSince, Instant offlineSince, long bytesPerSecond,
                           LocalDate eventDate, Instant eventStartTime, String eventStatus) {

    public enum Connection { CONNECTING, ONLINE, OFFLINE, BLOCKED }

    public record ActiveUpload(String fileName, long bytesSent, long totalBytes) {
        public int percent() {
            return totalBytes <= 0 ? 0 : (int) Math.min(100, bytesSent * 100 / totalBytes);
        }
    }
}
