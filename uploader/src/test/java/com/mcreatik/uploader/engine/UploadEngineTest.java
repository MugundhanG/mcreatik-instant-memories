package com.mcreatik.uploader.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mcreatik.uploader.FakeMcreatikServer;
import com.mcreatik.uploader.TestPhotos;
import com.mcreatik.uploader.api.ApiClient;
import com.mcreatik.uploader.queue.QueueStatus;
import com.mcreatik.uploader.queue.UploadQueue;
import com.mcreatik.uploader.source.FolderPhotoSource;

class UploadEngineTest {

    @TempDir
    Path folder;

    @TempDir
    Path dataDir;

    FakeMcreatikServer server;
    UploadQueue queue;
    UploadEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeMcreatikServer();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
        if (queue != null) {
            queue.close();
        }
        server.close();
    }

    private UploadEngine startEngine() {
        queue = new UploadQueue(dataDir.resolve("queue.db"));
        var source = new FolderPhotoSource(folder, Duration.ofMillis(50), Duration.ZERO, Clock.systemUTC());
        engine = new UploadEngine(new ApiClient(server.url(), FakeMcreatikServer.TOKEN), queue, source, 2,
                "test-device", new Backoff(Duration.ofMillis(20), Duration.ofMillis(200)),
                System::currentTimeMillis, Duration.ofMillis(100));
        engine.start();
        return engine;
    }

    private void stopEngine() {
        engine.close();
        queue.close();
        engine = null;
        queue = null;
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            Thread.sleep(20);
        }
    }

    @Test
    void detectsNewPhotoInFolderAndUploadsItAutomatically() throws Exception {
        startEngine();
        byte[] bytes = TestPhotos.jpeg();
        TestPhotos.write(folder, "IMG_0001.JPG", bytes);

        await("photo uploaded", () -> queue.counts().done() == 1);
        assertThat(server.completedShas()).containsExactly(FakeMcreatikServer.sha256(bytes));
        String photoId = server.photosBySha.get(FakeMcreatikServer.sha256(bytes));
        assertThat(server.stored.get(photoId)).isEqualTo(bytes);
        await("online", () -> engine.status().connection() == EngineStatus.Connection.ONLINE);
        assertThat(engine.status().eventName()).isEqualTo("Arun & Priya Wedding");
        assertThat(server.heartbeats.get()).isPositive();
    }

    /** The V1 failure test: internet drops mid-event, photographer keeps shooting, nothing is lost. */
    @Test
    void internetOutageKeepsPhotosQueuedAndResumesAutomatically() throws Exception {
        startEngine();
        await("online", () -> engine.status().connection() == EngineStatus.Connection.ONLINE);

        server.offline.set(true);
        List<String> shas = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            byte[] bytes = TestPhotos.jpeg();
            shas.add(FakeMcreatikServer.sha256(bytes));
            TestPhotos.write(folder, "IMG_00" + i + ".JPG", bytes);
        }
        await("all 4 queued", () -> queue.counts().total() == 4);
        await("offline detected", () -> engine.status().connection() == EngineStatus.Connection.OFFLINE);
        Thread.sleep(300); // several failed attempts while offline
        assertThat(queue.counts().failed()).as("network errors must never fail a photo").isZero();
        assertThat(queue.counts().done()).isZero();
        assertThat(queue.recent(10)).allSatisfy(i -> assertThat(i.status()).isIn(QueueStatus.QUEUED, QueueStatus.UPLOADING));

        server.offline.set(false);
        await("all uploaded after reconnect", () -> queue.counts().done() == 4);
        assertThat(server.completedShas()).containsExactlyInAnyOrderElementsOf(shas);
        assertThat(engine.status().connection()).isEqualTo(EngineStatus.Connection.ONLINE);
    }

    @Test
    void queueIsPersistedAndResumesAfterAppRestart() throws Exception {
        server.offline.set(true);
        startEngine();
        for (int i = 1; i <= 3; i++) {
            TestPhotos.write(folder, "IMG_" + i + ".JPG");
        }
        await("queued while offline", () -> queue.counts().total() == 3);
        stopEngine(); // laptop closed / app quit

        server.offline.set(false);
        startEngine(); // next launch
        await("uploaded after restart", () -> queue.counts().done() == 3);
        assertThat(server.completed).hasSize(3);
        assertThat(queue.counts().total()).as("no re-queued duplicates after rescan").isEqualTo(3);
    }

    @Test
    void duplicatesAreNeverUploadedTwice() throws Exception {
        startEngine();
        byte[] bytes = TestPhotos.jpeg();
        TestPhotos.write(folder, "IMG_1.JPG", bytes);
        await("uploaded", () -> queue.counts().done() == 1);
        int putsBefore = server.putCalls.get();

        // Same photo copied again into the folder (e.g. card re-imported): ignored locally.
        TestPhotos.write(folder, "backup/IMG_1.JPG", bytes);
        Thread.sleep(300);
        assertThat(queue.counts().total()).isEqualTo(1);

        // Photo already on the server from another laptop: the server says DUPLICATE, no bytes are sent.
        byte[] other = TestPhotos.jpeg();
        String otherSha = FakeMcreatikServer.sha256(other);
        server.photosBySha.put(otherSha, "already-there");
        server.completed.add("already-there");
        TestPhotos.write(folder, "IMG_2.JPG", other);
        await("duplicate recognised", () -> queue.counts().duplicate() == 1);
        assertThat(server.putCalls.get()).isEqualTo(putsBefore);
    }

    @Test
    void failedUploadIsRetriedWithBackoffUntilItSucceeds() throws Exception {
        server.failNextPuts.set(3);
        startEngine();
        TestPhotos.write(folder, "IMG_1.JPG");
        await("uploaded despite storage errors", () -> queue.counts().done() == 1);
        assertThat(server.putCalls.get()).isEqualTo(4);
        assertThat(queue.recent(1).getFirst().attempts()).isEqualTo(4);
    }

    @Test
    void permanentlyRejectedPhotoIsMarkedFailedAndCanBeRetried() throws Exception {
        server.rejectUploadsWithCode = "UNSUPPORTED_TYPE";
        startEngine();
        TestPhotos.write(folder, "IMG_1.JPG");
        await("failed", () -> queue.counts().failed() == 1);
        assertThat(queue.recent(1).getFirst().lastError()).isEqualTo("Rejected by test");

        server.rejectUploadsWithCode = null;
        engine.retryFailed();
        await("uploaded after manual retry", () -> queue.counts().done() == 1);
    }

    @Test
    void revokedTokenPausesUploadingWithoutLosingPhotos() throws Exception {
        server.revoked.set(true);
        startEngine();
        TestPhotos.write(folder, "IMG_1.JPG");
        await("blocked", () -> engine.status().connection() == EngineStatus.Connection.BLOCKED);
        await("queued", () -> queue.counts().total() == 1);
        Thread.sleep(300);
        assertThat(queue.counts().failed()).isZero();
        assertThat(engine.status().blockedReason()).contains("disconnected");

        server.revoked.set(false); // admin fixed it / new token
        await("resumed", () -> queue.counts().done() == 1);
    }

    @Test
    void fileDeletedBeforeUploadIsReportedNotRetriedForever() throws Exception {
        server.offline.set(true);
        startEngine();
        Path file = TestPhotos.write(folder, "IMG_1.JPG");
        await("queued", () -> queue.counts().total() == 1);
        java.nio.file.Files.delete(file);
        server.offline.set(false);
        await("failed", () -> queue.counts().failed() == 1);
        assertThat(queue.recent(1).getFirst().lastError()).contains("moved or deleted");
    }

    @Test
    void nonImageFileWithJpgExtensionIsNeverQueued() throws Exception {
        startEngine();
        TestPhotos.write(folder, "fake.jpg", "definitely not a jpeg".getBytes());
        TestPhotos.write(folder, "real.jpg");
        await("real one uploaded", () -> queue.counts().done() == 1);
        assertThat(queue.counts().total()).isEqualTo(1);
    }
}
