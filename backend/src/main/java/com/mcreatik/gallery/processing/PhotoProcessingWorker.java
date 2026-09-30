package com.mcreatik.gallery.processing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.mcreatik.gallery.config.GalleryProperties;
import com.mcreatik.gallery.photo.Photo;
import com.mcreatik.gallery.photo.PhotoReadyEvent;
import com.mcreatik.gallery.photo.PhotoRepository;
import com.mcreatik.gallery.photo.PhotoUploadedEvent;
import com.mcreatik.gallery.storage.StorageArea;
import com.mcreatik.gallery.storage.StorageService;

/**
 * Processes uploaded photos. The photos table is the job queue: workers claim rows with
 * FOR UPDATE SKIP LOCKED, so any number of workers (and backend instances) can run safely,
 * and nothing is lost on restart.
 */
@Component
public class PhotoProcessingWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PhotoProcessingWorker.class);

    private final PhotoRepository photos;
    private final PhotoProcessingPipeline pipeline;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher publisher;
    private final StorageService storage;
    private final GalleryProperties.Processing config;

    private final Object signal = new Object();
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    public PhotoProcessingWorker(PhotoRepository photos, PhotoProcessingPipeline pipeline, JdbcClient jdbc,
                                 TransactionTemplate tx, ApplicationEventPublisher publisher, StorageService storage,
                                 GalleryProperties properties) {
        this.photos = photos;
        this.pipeline = pipeline;
        this.jdbc = jdbc;
        this.tx = tx;
        this.publisher = publisher;
        this.storage = storage;
        this.config = properties.processing();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startWorkers() {
        if (!config.enabled() || running) {
            return;
        }
        running = true;
        for (int i = 0; i < config.workers(); i++) {
            Thread t = Thread.ofPlatform().name("photo-worker-" + i).daemon(true).start(this::loop);
            threads.add(t);
        }
        log.info("Started {} photo processing workers", config.workers());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPhotoUploaded(PhotoUploadedEvent event) {
        wake();
    }

    public void wake() {
        synchronized (signal) {
            signal.notifyAll();
        }
    }

    private void loop() {
        while (running) {
            try {
                if (!processNext()) {
                    synchronized (signal) {
                        signal.wait(2_000);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("Photo worker loop error", e);
                sleepQuietly();
            }
        }
    }

    /** Claims and processes one photo. Returns false if the queue was empty. Public for tests. */
    public boolean processNext() {
        Optional<UUID> claimed = tx.execute(s -> jdbc.sql("""
                        UPDATE photos SET status = 'PROCESSING', processing_started_at = now(),
                               processing_attempts = processing_attempts + 1, updated_at = now()
                        WHERE id = (SELECT id FROM photos WHERE status = 'UPLOADED'
                                    ORDER BY uploaded_at LIMIT 1 FOR UPDATE SKIP LOCKED)
                        RETURNING id""")
                .query(UUID.class).optional());
        if (claimed == null || claimed.isEmpty()) {
            return false;
        }
        process(claimed.get());
        return true;
    }

    private void process(UUID photoId) {
        Photo photo = photos.findById(photoId).orElse(null);
        if (photo == null) {
            return;
        }
        PhotoProcessingContext ctx = new PhotoProcessingContext(photo);
        try {
            pipeline.run(ctx);
        } catch (PhotoProcessingException e) {
            log.warn("Photo {} failed permanently: {}", photoId, e.getMessage());
            finish(photoId, p -> p.markFailed(e.getMessage()));
            return;
        } catch (Exception | OutOfMemoryError e) {
            boolean giveUp = photo.getProcessingAttempts() >= config.maxAttempts();
            log.warn("Photo {} processing error (attempt {}/{}): {}", photoId, photo.getProcessingAttempts(),
                    config.maxAttempts(), e.toString());
            finish(photoId, p -> {
                if (giveUp) {
                    p.markFailed("Processing failed: " + e.getMessage());
                } else {
                    p.returnToQueue("Retrying after error: " + e.getMessage());
                }
            });
            return;
        }

        boolean stillExists = finish(photoId, p -> p.markReady(ctx.width(), ctx.height(), ctx.capturedAt(),
                ctx.optimizedKey(), ctx.thumbnailKey(), ctx.detectedMimeType()));
        if (stillExists) {
            publisher.publishEvent(new PhotoReadyEvent(photo.getEventId(), photoId));
        } else {
            // Deleted by an admin while processing: remove the derivatives we just wrote.
            storage.delete(StorageArea.MEDIA, ctx.optimizedKey());
            storage.delete(StorageArea.MEDIA, ctx.thumbnailKey());
        }
    }

    private boolean finish(UUID photoId, java.util.function.Consumer<Photo> change) {
        Boolean done = tx.execute(s -> photos.findById(photoId).map(p -> {
            change.accept(p);
            return true;
        }).orElse(false));
        return Boolean.TRUE.equals(done);
    }

    /** Re-queues photos stuck in PROCESSING because a worker/instance died mid-way. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void requeueStale() {
        Integer n = tx.execute(s -> photos.requeueStale(Instant.now().minus(config.staleAfter())));
        if (n != null && n > 0) {
            log.warn("Re-queued {} stale photos", n);
            wake();
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void start() {
        // Workers start on ApplicationReadyEvent so the web server and migrations are fully up first.
    }

    @Override
    public void stop() {
        running = false;
        wake();
        for (Thread t : threads) {
            try {
                t.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
