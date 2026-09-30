package com.mcreatik.uploader.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadQueueTest {

    @TempDir
    Path dir;

    private final AtomicLong now = new AtomicLong(1_000_000);

    private UploadQueue open() {
        return new UploadQueue(dir.resolve("queue.db"), now::get);
    }

    private static String sha(char c) {
        return String.valueOf(c).repeat(64);
    }

    @Test
    void queuesFilesAndIgnoresDuplicateContent() {
        try (UploadQueue q = open()) {
            assertThat(q.enqueue(Path.of("/cam/IMG_1.JPG"), "IMG_1.JPG", 100, 5, sha('a'), "image/jpeg"))
                    .isEqualTo(UploadQueue.EnqueueResult.ADDED);
            assertThat(q.enqueue(Path.of("/cam/copy/IMG_1.JPG"), "IMG_1.JPG", 100, 9, sha('a'), "image/jpeg"))
                    .isEqualTo(UploadQueue.EnqueueResult.ALREADY_QUEUED);
            assertThat(q.counts().queued()).isEqualTo(1);
            assertThat(q.isKnown(Path.of("/cam/IMG_1.JPG"), 100, 5)).isTrue();
            assertThat(q.isKnown(Path.of("/cam/IMG_1.JPG"), 100, 6)).isFalse();
        }
    }

    @Test
    void queueSurvivesRestartAndInFlightUploadsResume() {
        try (UploadQueue q = open()) {
            q.enqueue(Path.of("/cam/1.jpg"), "1.jpg", 10, 1, sha('1'), "image/jpeg");
            q.enqueue(Path.of("/cam/2.jpg"), "2.jpg", 10, 1, sha('2'), "image/jpeg");
            q.enqueue(Path.of("/cam/3.jpg"), "3.jpg", 10, 1, sha('3'), "image/jpeg");
            QueueItem inFlight = q.claimNext().orElseThrow();
            assertThat(inFlight.status()).isEqualTo(QueueStatus.UPLOADING);
            q.markDone(q.claimNext().orElseThrow().id(), "photo-2", false);
            // app crashes here with photo 1 mid-upload
        }
        try (UploadQueue q = open()) {
            assertThat(q.counts()).isEqualTo(new QueueCounts(1, 1, 1, 0, 0));
            assertThat(q.resetInFlight()).isEqualTo(1);
            assertThat(q.counts()).isEqualTo(new QueueCounts(2, 0, 1, 0, 0));
            assertThat(q.claimNext().orElseThrow().fileName()).isEqualTo("1.jpg");
        }
    }

    @Test
    void retryIsDelayedUntilBackoffExpiresOrConnectivityReturns() {
        try (UploadQueue q = open()) {
            q.enqueue(Path.of("/cam/1.jpg"), "1.jpg", 10, 1, sha('1'), "image/jpeg");
            QueueItem item = q.claimNext().orElseThrow();
            q.markRetry(item.id(), "No connection", now.get() + 60_000);

            assertThat(q.claimNext()).isEmpty();
            assertThat(q.nextDueAt()).contains(now.get() + 60_000);
            now.addAndGet(60_000);
            QueueItem again = q.claimNext().orElseThrow();
            assertThat(again.attempts()).isEqualTo(2);
            assertThat(again.lastError()).isEqualTo("No connection");

            q.markRetry(again.id(), "No connection", now.get() + 300_000);
            assertThat(q.expediteRetries()).isEqualTo(1);
            assertThat(q.claimNext()).isPresent();
        }
    }

    @Test
    void failedItemsCanBeRetriedOnRequest() {
        try (UploadQueue q = open()) {
            q.enqueue(Path.of("/cam/1.jpg"), "1.jpg", 10, 1, sha('1'), "image/jpeg");
            q.markFailed(q.claimNext().orElseThrow().id(), "Rejected");
            assertThat(q.counts().failed()).isEqualTo(1);
            assertThat(q.claimNext()).isEmpty();
            assertThat(q.retryFailed()).isEqualTo(1);
            assertThat(q.claimNext().orElseThrow().attempts()).isEqualTo(1);
        }
    }

    @Test
    void duplicateOutcomeIsTrackedSeparately() {
        try (UploadQueue q = open()) {
            q.enqueue(Path.of("/cam/1.jpg"), "1.jpg", 10, 1, sha('1'), "image/jpeg");
            q.markDone(q.claimNext().orElseThrow().id(), "p1", true);
            assertThat(q.counts().duplicate()).isEqualTo(1);
            assertThat(q.recent(10).getFirst().photoId()).isEqualTo("p1");
        }
    }
}
