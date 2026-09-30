package com.mcreatik.uploader.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.mcreatik.uploader.api.ApiClient;
import com.mcreatik.uploader.api.ApiException;
import com.mcreatik.uploader.api.ApiModels;
import com.mcreatik.uploader.queue.QueueCounts;
import com.mcreatik.uploader.queue.QueueItem;
import com.mcreatik.uploader.queue.UploadQueue;
import com.mcreatik.uploader.source.PhotoSource;

/**
 * Source → persistent queue → N upload workers → McreatiK.
 *
 * <p>Reliability rules:
 * <ul>
 *   <li>A photo is written to the local queue before any network activity.</li>
 *   <li>Network/server errors never fail a photo: it is re-queued with exponential backoff.</li>
 *   <li>When connectivity returns (any successful call), all backoffs are cut short.</li>
 *   <li>If the token is revoked or the event archived, uploading pauses; nothing is dropped.</li>
 *   <li>On restart, in-flight photos return to the queue and the folder is rescanned.</li>
 * </ul>
 */
public class UploadEngine implements AutoCloseable {

    private static final Logger log = Logger.getLogger(UploadEngine.class.getName());
    public static final String APP_VERSION = "0.1.0";

    private final ApiClient api;
    private final UploadQueue queue;
    private final PhotoSource source;
    private final int concurrency;
    private final String deviceId;
    private final Backoff backoff;
    private final LongSupplier clock;
    private final Duration heartbeatInterval;

    private final Object signal = new Object();
    private final Map<Long, EngineStatus.ActiveUpload> active = new ConcurrentHashMap<>();
    private final List<Thread> workers = new ArrayList<>();
    private ScheduledExecutorService scheduler;

    private volatile boolean running;
    private volatile EngineStatus.Connection connection = EngineStatus.Connection.CONNECTING;
    private volatile String blockedReason;
    private volatile String lastError;
    private volatile ApiModels.Me me;

    public UploadEngine(ApiClient api, UploadQueue queue, PhotoSource source, int concurrency, String deviceId) {
        this(api, queue, source, concurrency, deviceId, Backoff.DEFAULT, System::currentTimeMillis, Duration.ofSeconds(15));
    }

    public UploadEngine(ApiClient api, UploadQueue queue, PhotoSource source, int concurrency, String deviceId,
                        Backoff backoff, LongSupplier clock, Duration heartbeatInterval) {
        this.api = api;
        this.queue = queue;
        this.source = source;
        this.concurrency = concurrency;
        this.deviceId = deviceId;
        this.backoff = backoff;
        this.clock = clock;
        this.heartbeatInterval = heartbeatInterval;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        int reset = queue.resetInFlight();
        if (reset > 0) {
            log.info("Resuming " + reset + " photo(s) that were uploading when the app stopped");
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::heartbeat, 0, heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
        for (int i = 0; i < concurrency; i++) {
            workers.add(Thread.ofPlatform().name("upload-worker-" + i).daemon(true).start(this::workerLoop));
        }
        source.start(this::onPhoto);
        log.info("Watching " + source.describe() + " with " + concurrency + " upload worker(s)");
    }

    // ---- intake ----

    /** Called by the source for every stable file. Hashes and queues it (idempotent). */
    public void onPhoto(Path file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            long size = attrs.size();
            long modified = attrs.lastModifiedTime().toMillis();
            if (queue.isKnown(file, size, modified)) {
                return;
            }
            String mime = PhotoFiles.sniffMimeType(file);
            if (mime == null) {
                log.warning("Skipping " + file.getFileName() + ": not a JPEG or PNG image");
                return;
            }
            String sha = PhotoFiles.sha256(file);
            if (queue.enqueue(file, file.getFileName().toString(), size, modified, sha, mime)
                    == UploadQueue.EnqueueResult.ADDED) {
                wakeWorkers();
            }
        } catch (NoSuchFileException e) {
            // removed between scan and hash; nothing to do
        } catch (IOException e) {
            log.log(Level.WARNING, "Cannot read " + file + ": " + e.getMessage());
        }
    }

    // ---- workers ----

    private void workerLoop() {
        while (running) {
            try {
                if (connection == EngineStatus.Connection.BLOCKED) {
                    waitForSignal(5_000);
                    continue;
                }
                var item = queue.claimNext();
                if (item.isPresent()) {
                    uploadOne(item.get());
                } else {
                    long wait = queue.nextDueAt().map(due -> due - clock.getAsLong()).orElse(1_000L);
                    waitForSignal(Math.max(50, Math.min(1_000, wait)));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.log(Level.SEVERE, "Upload worker error", e);
                lastError = e.getMessage();
                sleepQuietly(1_000);
            }
        }
    }

    /** Uploads one queued photo. Package-private so tests can drive the engine step by step. */
    void uploadOne(QueueItem item) {
        try {
            if (!Files.exists(item.path())) {
                queue.markFailed(item.id(), ApiClient.FILE_GONE);
                return;
            }
            if (Files.size(item.path()) != item.size()) {
                // Rewritten after queuing; the scanner will pick up the new version as a new photo.
                queue.markFailed(item.id(), "File changed after it was queued; the new version will be uploaded");
                return;
            }
            ApiModels.UploadSession session = api.createUpload(new ApiModels.CreateUploadRequest(
                    item.fileName(), item.size(), item.mimeType(), item.sha256()));
            markOnline();
            if (session.isDuplicate()) {
                queue.markDone(item.id(), session.photoId(), true);
                return;
            }
            active.put(item.id(), new EngineStatus.ActiveUpload(item.fileName(), 0, item.size()));
            api.putFile(session.upload(), item.path(),
                    sent -> active.put(item.id(), new EngineStatus.ActiveUpload(item.fileName(), sent, item.size())));
            api.complete(session.photoId());
            queue.markDone(item.id(), session.photoId(), false);
            lastError = null;
        } catch (ApiException e) {
            handleFailure(item, e);
        } catch (IOException e) {
            queue.markRetry(item.id(), e.getMessage(), clock.getAsLong() + backoff.delayMillis(item.attempts()));
        } finally {
            active.remove(item.id());
        }
    }

    private void handleFailure(QueueItem item, ApiException e) {
        lastError = item.fileName() + ": " + e.getMessage();
        switch (e.kind()) {
            case TRANSIENT -> {
                if (e.status() == 0) {
                    markOffline();
                }
                long delay = backoff.delayMillis(item.attempts());
                queue.markRetry(item.id(), e.getMessage(), clock.getAsLong() + delay);
                log.info("Will retry " + item.fileName() + " in " + delay / 1000 + "s: " + e.getMessage());
            }
            case BLOCKED -> {
                // Keep the photo, pause everything until the setup is fixed.
                queue.markRetry(item.id(), e.getMessage(), clock.getAsLong());
                block(e.getMessage());
            }
            case PERMANENT -> {
                queue.markFailed(item.id(), e.getMessage());
                log.warning("Failed " + item.fileName() + ": " + e.getMessage());
            }
        }
    }

    // ---- heartbeat & connectivity ----

    /** Presence + connectivity probe. Package-private for tests. */
    void heartbeat() {
        try {
            if (me == null) {
                me = api.me();
            }
            QueueCounts counts = queue.counts();
            ApiModels.HeartbeatResponse response = api.heartbeat(new ApiModels.HeartbeatRequest(
                    deviceId, counts.pending(), counts.failed(), lastError, APP_VERSION));
            if (response.acceptingUploads()) {
                if (connection == EngineStatus.Connection.BLOCKED) {
                    blockedReason = null;
                    connection = EngineStatus.Connection.ONLINE;
                    wakeWorkers();
                }
                markOnline();
            } else {
                block("This event is closed for uploads (" + response.eventStatus() + ")");
            }
        } catch (ApiException e) {
            switch (e.kind()) {
                case BLOCKED -> block(e.getMessage());
                case TRANSIENT, PERMANENT -> {
                    if (connection != EngineStatus.Connection.BLOCKED) {
                        markOffline();
                    }
                }
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Heartbeat error", e);
        }
    }

    private void markOnline() {
        if (connection == EngineStatus.Connection.BLOCKED) {
            return;
        }
        EngineStatus.Connection previous = connection;
        connection = EngineStatus.Connection.ONLINE;
        if (previous != EngineStatus.Connection.ONLINE) {
            int expedited = queue.expediteRetries();
            if (previous == EngineStatus.Connection.OFFLINE) {
                log.info("Back online. Resuming " + expedited + " waiting photo(s) now.");
            }
            wakeWorkers();
        }
    }

    private void markOffline() {
        if (connection != EngineStatus.Connection.OFFLINE) {
            log.warning("Connection lost. Photos stay safely queued and will upload automatically.");
        }
        connection = EngineStatus.Connection.OFFLINE;
    }

    private void block(String reason) {
        if (connection != EngineStatus.Connection.BLOCKED) {
            log.severe("Uploading paused: " + reason);
        }
        blockedReason = reason;
        connection = EngineStatus.Connection.BLOCKED;
    }

    // ---- controls ----

    public int retryFailed() {
        int n = queue.retryFailed();
        wakeWorkers();
        return n;
    }

    public EngineStatus status() {
        ApiModels.Me info = me;
        return new EngineStatus(connection, blockedReason,
                info == null ? null : info.eventName(), info == null ? null : info.uploaderName(),
                info == null ? null : info.galleryUrl(), source.describe(), queue.counts(),
                List.copyOf(active.values()), lastError);
    }

    public UploadQueue queue() {
        return queue;
    }

    private void wakeWorkers() {
        synchronized (signal) {
            signal.notifyAll();
        }
    }

    private void waitForSignal(long millis) throws InterruptedException {
        synchronized (signal) {
            signal.wait(millis);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        source.close();
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        wakeWorkers();
        for (Thread t : workers) {
            try {
                // An upload in progress is abandoned; it restarts from the queue next time.
                t.join(2_000);
                t.interrupt();
                t.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        workers.clear();
    }
}
