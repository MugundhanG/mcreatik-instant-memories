package com.mcreatik.gallery.face;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.mcreatik.gallery.config.GalleryProperties;
import com.mcreatik.gallery.photo.PhotoReadyEvent;
import com.mcreatik.gallery.storage.StorageArea;
import com.mcreatik.gallery.storage.StorageService;

/**
 * Puts the faces of guest photos into their event's collection. One thread, running only when face search is on.
 * Like the photo worker, the photos table is the queue (rows are claimed with FOR UPDATE SKIP LOCKED); turning the
 * add-on on for an event with photos already in it backfills them the same way. The claim also pushes the photo's
 * next attempt out (30 s, then x4 each attempt), so a failure retries with backoff and a crash is picked up again.
 * Only the 2048 px web JPEG is downloaded (bytes only: nothing is decoded on this server).
 */
@Component
public class FaceIndexWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FaceIndexWorker.class);

    private record Claimed(UUID photoId, UUID eventId, String key) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final StorageService storage;
    private final FaceSearchProvider provider;
    private final FaceSearchService service;
    private final GalleryProperties.FaceSearch config;

    private final Object signal = new Object();
    private Thread thread;
    private volatile boolean running;

    public FaceIndexWorker(JdbcClient jdbc, TransactionTemplate tx, StorageService storage, FaceSearchProvider provider,
                           FaceSearchService service, GalleryProperties properties) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.storage = storage;
        this.provider = provider;
        this.service = service;
        this.config = properties.faceSearch();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startWorker() {
        if (!config.enabled() || !config.workerEnabled() || running) {
            return;
        }
        running = true;
        thread = Thread.ofPlatform().name("face-worker").daemon(true).start(this::loop);
        log.info("Started the face index worker");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPhotoReady(PhotoReadyEvent event) {
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
                        signal.wait(5_000);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("Face worker loop error", e);
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Claims and indexes one photo. Returns false if nothing is waiting. Public for tests. */
    public boolean processNext() {
        Optional<Claimed> claimed = tx.execute(s -> jdbc.sql("""
                        UPDATE photos SET face_attempts = face_attempts + 1,
                               face_next_attempt_at = now() + interval '30 seconds' * power(4, face_attempts)
                        WHERE id = (SELECT p.id FROM photos p JOIN events e ON e.id = p.event_id
                                    WHERE p.gallery = 'GUEST' AND p.status = 'READY' AND p.face_indexed_at IS NULL
                                      AND p.optimized_storage_key IS NOT NULL AND p.face_attempts < :max
                                      AND (p.face_next_attempt_at IS NULL OR p.face_next_attempt_at <= now())
                                      AND e.face_search_enabled AND e.photos_deleted_at IS NULL
                                    ORDER BY p.ready_at LIMIT 1 FOR UPDATE OF p SKIP LOCKED)
                        RETURNING id, event_id, optimized_storage_key""")
                .param("max", config.maxAttempts())
                .query((rs, n) -> new Claimed(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)))
                .optional());
        if (claimed == null || claimed.isEmpty()) {
            return false;
        }
        index(claimed.get());
        return true;
    }

    private void index(Claimed c) {
        String collection = service.collectionName(c.eventId());
        List<String> faceIds;
        try {
            service.ensureCollection(c.eventId());
            byte[] jpeg = storage.read(StorageArea.MEDIA, c.key());
            try {
                faceIds = provider.indexFaces(collection, c.photoId(), jpeg);
            } catch (FaceSearchProvider.BadImageException e) {
                log.warn("Photo {}: the face engine could not read its web version; no faces recorded", c.photoId());
                faceIds = List.of();
            }
        } catch (Exception e) {
            log.warn("Face indexing of photo {} failed (will retry later): {}", c.photoId(), e.toString());
            return;
        }
        List<String> ids = faceIds;
        Boolean kept = tx.execute(s -> {
            // Gone (deleted while indexing) or add-on switched off meanwhile: the rows are not wanted.
            int updated = jdbc.sql("""
                            UPDATE photos SET face_indexed_at = now(), face_next_attempt_at = NULL
                            WHERE id = :photo AND EXISTS (SELECT 1 FROM events WHERE id = :event AND face_search_enabled)""")
                    .param("photo", c.photoId()).param("event", c.eventId()).update();
            if (updated == 0) {
                return false;
            }
            for (String faceId : ids) {
                jdbc.sql("INSERT INTO photo_faces (photo_id, event_id, face_id) VALUES (:photo, :event, :face) ON CONFLICT DO NOTHING")
                        .param("photo", c.photoId()).param("event", c.eventId()).param("face", faceId).update();
            }
            return true;
        });
        if (!Boolean.TRUE.equals(kept) && !ids.isEmpty()) {
            try {
                provider.deleteFaces(collection, ids);
            } catch (Exception e) {
                log.warn("Could not remove faces of the vanished photo {}: {}", c.photoId(), e.toString());
            }
        }
    }

    @Override
    public void start() {
        // The thread starts on ApplicationReadyEvent, like the photo workers.
    }

    @Override
    public void stop() {
        running = false;
        wake();
        if (thread != null) {
            try {
                thread.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
