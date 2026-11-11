package com.mcreatik.uploader.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.time.Instant;
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
import com.mcreatik.uploader.prepare.PhotoPreparer;
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
 *   <li>Paused (privacy pause, from the app or the dashboard): nothing more reaches the gallery from this camera.
 *       Photos not uploaded yet, and every new one, are moved to {@link PhotoFiles#PAUSED_DIR} and never uploaded.</li>
 * </ul>
 *
 * <p>By default each photo is prepared on the laptop (2048 px web + 640 px thumbnail, ~1 MB in total) and only
 * those are uploaded, so photos reach guests within seconds even on a weak venue uplink and a small server. If
 * preparation fails or the server does not support it, the original is uploaded for server-side processing.
 */
public class UploadEngine implements AutoCloseable {

    private static final Logger log = Logger.getLogger(UploadEngine.class.getName());
    /** The installer's version (jpackage sets it), else the build's, for local runs. */
    public static final String APP_VERSION = System.getProperty("jpackage.app-version", "0.2.0");

    private final ApiClient api;
    private final UploadQueue queue;
    private final PhotoSource source;
    private final int concurrency;
    private final String deviceId;
    private final Backoff backoff;
    private final LongSupplier clock;
    private final Duration heartbeatInterval;
    private final boolean prepareLocally;

    private final Object signal = new Object();
    private final Map<Long, EngineStatus.ActiveUpload> active = new ConcurrentHashMap<>();
    private final List<Thread> workers = new ArrayList<>();
    private ScheduledExecutorService scheduler;

    private volatile boolean running;
    private volatile EngineStatus.Connection connection = EngineStatus.Connection.CONNECTING;
    private volatile String blockedReason;
    private volatile String lastError;
    private volatile ApiModels.Me me;
    private volatile Instant onlineSince;
    private volatile Instant offlineSince;
    /** Privacy pause. The server's flag is the truth once known; a local change wins until the server has it. */
    private volatile boolean paused;
    /** A pause/resume pressed here that the server hasn't confirmed yet (null when none). */
    private volatile Boolean pendingPause;
    /** The camera's name from the latest heartbeat (a rename in the dashboard shows within seconds). */
    private volatile String liveName;
    /** This camera's photo folder, for the paused sub-folder (null: keep paused photos where they are). */
    private volatile Path photoFolder;
    /** (finished-at millis, bytes, transfer millis) of recent uploads, for the upload speed. */
    private final java.util.Deque<long[]> finished = new java.util.ArrayDeque<>();
    static final long SPEED_WINDOW_MS = 30_000;
    /** Heartbeats since the event details were last fetched: they are re-read every {@link #ME_REFRESH_BEATS}. */
    private int beatsSinceMe;
    /** Every 4 heartbeats (one minute at the normal 15 s), so a renamed event shows without a restart. */
    static final int ME_REFRESH_BEATS = 4;

    public UploadEngine(ApiClient api, UploadQueue queue, PhotoSource source, int concurrency, String deviceId) {
        this(api, queue, source, concurrency, deviceId, Backoff.DEFAULT, System::currentTimeMillis, Duration.ofSeconds(15));
    }

    public UploadEngine(ApiClient api, UploadQueue queue, PhotoSource source, int concurrency, String deviceId,
                        boolean prepareLocally) {
        this(api, queue, source, concurrency, deviceId, Backoff.DEFAULT, System::currentTimeMillis,
                Duration.ofSeconds(15), prepareLocally);
    }

    public UploadEngine(ApiClient api, UploadQueue queue, PhotoSource source, int concurrency, String deviceId,
                        Backoff backoff, LongSupplier clock, Duration heartbeatInterval) {
        this(api, queue, source, concurrency, deviceId, backoff, clock, heartbeatInterval, true);
    }

    public UploadEngine(ApiClient api, UploadQueue queue, PhotoSource source, int concurrency, String deviceId,
                        Backoff backoff, LongSupplier clock, Duration heartbeatInterval, boolean prepareLocally) {
        this.prepareLocally = prepareLocally;
        this.api = api;
        this.queue = queue;
        this.source = source;
        this.concurrency = concurrency;
        this.deviceId = deviceId;
        this.backoff = backoff;
        this.clock = clock;
        this.heartbeatInterval = heartbeatInterval;
    }

    /** Where this camera's photos are; paused photos go into {@link PhotoFiles#PAUSED_DIR} inside it. */
    public void setPhotoFolder(Path folder) {
        this.photoFolder = folder;
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
            if (paused) {
                holdBack(file);
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
                if (connection == EngineStatus.Connection.BLOCKED || paused) {
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
            PhotoPreparer.Prepared prepared = prepareLocally ? prepare(item) : null;
            long transferStart = clock.getAsLong();
            ApiModels.UploadSession session = api.createUpload(new ApiModels.CreateUploadRequest(
                    item.fileName(), item.size(), item.mimeType(), item.sha256(), versions(prepared)));
            markOnline();
            if (session.isDuplicate()) {
                queue.markDone(item.id(), session.photoId(), true);
                return;
            }
            if (prepared != null && session.acceptsPrepared()) {
                long webSize = prepared.web().length;
                long total = webSize + prepared.thumbnail().length;
                active.put(item.id(), new EngineStatus.ActiveUpload(item.fileName(), 0, total));
                api.putBytes(session.webUpload(), prepared.web(),
                        sent -> active.put(item.id(), new EngineStatus.ActiveUpload(item.fileName(), sent, total)));
                api.putBytes(session.thumbnailUpload(), prepared.thumbnail(),
                        sent -> active.put(item.id(), new EngineStatus.ActiveUpload(item.fileName(), webSize + sent, total)));
            } else if (session.upload() != null) {
                active.put(item.id(), new EngineStatus.ActiveUpload(item.fileName(), 0, item.size()));
                api.putFile(session.upload(), item.path(),
                        sent -> active.put(item.id(), new EngineStatus.ActiveUpload(item.fileName(), sent, item.size())));
            } else {
                throw new ApiException(ApiException.Kind.TRANSIENT, 502, "NO_UPLOAD_URL",
                        "Server did not return an upload address");
            }
            api.complete(session.photoId());
            queue.markDone(item.id(), session.photoId(), false);
            EngineStatus.ActiveUpload done = active.get(item.id());
            recordFinished(done == null ? 0 : done.totalBytes(), clock.getAsLong() - transferStart);
            lastError = null;
        } catch (ApiException e) {
            handleFailure(item, e);
        } catch (IOException e) {
            queue.markRetry(item.id(), e.getMessage(), clock.getAsLong() + backoff.delayMillis(item.attempts()));
        } finally {
            active.remove(item.id());
        }
    }

    /** Null when the photo cannot be prepared here: the original is then sent and the server decides. */
    private PhotoPreparer.Prepared prepare(QueueItem item) {
        try {
            return PhotoPreparer.prepare(item.path());
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            log.warning("Could not prepare " + item.fileName() + " on this computer (" + e.getMessage()
                    + "); uploading the original instead");
            return null;
        }
    }

    private static ApiModels.PreparedVersions versions(PhotoPreparer.Prepared p) {
        return p == null ? null : new ApiModels.PreparedVersions(p.web().length, p.thumbnail().length, p.width(),
                p.height(), p.capturedAt() == null ? null : p.capturedAt().toString());
    }

    private void handleFailure(QueueItem item, ApiException e) {
        if (PAUSED_CODE.equals(e.code())) {
            // Paused in the dashboard before this laptop heard about it: this photo stays private too.
            holdBack(item.path());
            queue.remove(item.id());
            applyPaused(true);
            return;
        }
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

    /** The server's answer to an upload from a paused camera. */
    static final String PAUSED_CODE = "UPLOADER_PAUSED";

    /** Moves a photo received while paused into the camera's "Not uploaded (paused)" folder. */
    private void holdBack(Path file) {
        Path folder = photoFolder;
        if (folder == null || !Files.exists(file)) {
            return;
        }
        try {
            Path target = folder.resolve(PhotoFiles.PAUSED_DIR);
            Files.createDirectories(target);
            String name = file.getFileName().toString();
            Path dest = target.resolve(name);
            for (int n = 2; Files.exists(dest); n++) {
                int dot = name.lastIndexOf('.');
                dest = target.resolve(dot < 0 ? name + " (" + n + ")" : name.substring(0, dot) + " (" + n + ")" + name.substring(dot));
            }
            Files.move(file, dest);
            log.info("Paused: kept " + name + " on this computer (not uploaded)");
        } catch (IOException e) {
            log.warning("Paused: couldn't move " + file.getFileName() + " aside: " + e.getMessage());
        }
    }

    /** Photos kept back while paused (in the "Not uploaded (paused)" folder). */
    public int pausedPhotos() {
        Path folder = photoFolder;
        if (folder == null) {
            return 0;
        }
        Path dir = folder.resolve(PhotoFiles.PAUSED_DIR);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var files = Files.list(dir)) {
            return (int) files.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    public boolean isPaused() {
        return paused;
    }

    /** Applies a pause or resume (from here or the dashboard). Going paused keeps back what hasn't uploaded yet. */
    private void applyPaused(boolean pause) {
        synchronized (pauseLock) {
            boolean was = paused;
            paused = pause;
            if (pause && !was) {
                for (QueueItem item : queue.waiting()) {
                    holdBack(item.path());
                    queue.remove(item.id());
                }
            } else if (!pause && was) {
                wakeWorkers();
            }
        }
    }

    private final Object pauseLock = new Object();

    /**
     * Pause or resume from this app. Takes effect here at once; the server is told now if it can be reached, otherwise
     * on the next heartbeat, so the dashboard shows it too.
     */
    public void setPaused(boolean pause) {
        pendingPause = pause;
        applyPaused(pause);
        ScheduledExecutorService s = scheduler;
        if (s != null && !s.isShutdown()) {
            s.execute(this::pushPause);
        }
    }

    private void pushPause() {
        Boolean wanted = pendingPause;
        if (wanted == null) {
            return;
        }
        try {
            ApiModels.UpdateSelfResponse r = api.updateSelf(null, wanted);
            if (wanted.equals(pendingPause)) {
                pendingPause = null;
                applyPaused(r.paused());
            }
        } catch (ApiException e) {
            // offline or an older server: keep the local choice and try again with the next heartbeat
        }
    }

    /** Renames this camera on the server (both the app and the dashboard show the new name). */
    public String rename(String name) throws ApiException {
        ApiModels.UpdateSelfResponse r = api.updateSelf(name, null);
        liveName = r.name();
        me = null; // re-read the event details (and the name) on the next heartbeat
        return r.name();
    }

    // ---- heartbeat & connectivity ----

    /** Presence + connectivity probe. Package-private for tests. */
    void heartbeat() {
        try {
            if (me == null || ++beatsSinceMe >= ME_REFRESH_BEATS) {
                me = api.me();
                beatsSinceMe = 0;
                if (pendingPause == null) {
                    applyPaused(me.paused()); // on start: a camera paused in the dashboard stays paused
                }
            }
            QueueCounts counts = queue.counts();
            ApiModels.HeartbeatResponse response = api.heartbeat(new ApiModels.HeartbeatRequest(
                    deviceId, counts.pending(), counts.failed(), lastError, APP_VERSION));
            if (pendingPause != null) {
                pushPause();
            } else if (paused != response.paused()) {
                log.info(response.paused() ? "Paused from the dashboard: new photos stay on this computer"
                        : "Resumed from the dashboard");
                applyPaused(response.paused());
            }
            if (response.uploaderName() != null) {
                liveName = response.uploaderName();
            }
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
            onlineSince = Instant.ofEpochMilli(clock.getAsLong());
            offlineSince = null;
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
            offlineSince = Instant.ofEpochMilli(clock.getAsLong());
            onlineSince = null;
        }
        connection = EngineStatus.Connection.OFFLINE;
    }

    private void block(String reason) {
        if (connection != EngineStatus.Connection.BLOCKED) {
            log.severe("Uploading paused: " + reason);
        }
        blockedReason = reason;
        connection = EngineStatus.Connection.BLOCKED;
        onlineSince = null;
    }

    // ---- controls ----

    public int retryFailed() {
        int n = queue.retryFailed();
        wakeWorkers();
        return n;
    }

    public EngineStatus status() {
        ApiModels.Me info = me;
        String name = liveName != null ? liveName : info == null ? null : info.uploaderName();
        return new EngineStatus(connection, blockedReason,
                info == null ? null : info.eventName(), name,
                info == null ? null : info.galleryUrl(), source.describe(), queue.counts(),
                List.copyOf(active.values()), lastError, onlineSince, offlineSince, bytesPerSecond(),
                info == null ? null : info.eventDateValue(), info == null ? null : info.eventStartValue(),
                info == null ? null : info.eventStatus(), paused, pausedPhotos());
    }

    private void recordFinished(long bytes, long transferMillis) {
        synchronized (finished) {
            finished.addLast(new long[] {clock.getAsLong(), bytes, Math.max(1, transferMillis)});
        }
    }

    /**
     * Upload speed: bytes sent divided by the time spent sending them, over uploads that finished in the last
     * {@link #SPEED_WINDOW_MS}. 0 when nothing finished in that time. Parallel uploads overlap, so with two workers
     * this is per-photo speed, a fair "how fast is the internet" figure.
     */
    long bytesPerSecond() {
        long now = clock.getAsLong();
        long bytes = 0;
        long millis = 0;
        synchronized (finished) {
            while (!finished.isEmpty() && finished.peekFirst()[0] < now - SPEED_WINDOW_MS) {
                finished.removeFirst();
            }
            for (long[] f : finished) {
                bytes += f[1];
                millis += f[2];
            }
        }
        return millis == 0 ? 0 : bytes * 1000 / millis;
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
