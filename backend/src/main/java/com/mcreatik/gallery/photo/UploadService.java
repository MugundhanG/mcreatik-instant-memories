package com.mcreatik.gallery.photo;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.mcreatik.gallery.common.ApiException;
import com.mcreatik.gallery.config.GalleryProperties;
import com.mcreatik.gallery.event.Event;
import com.mcreatik.gallery.event.EventRepository;
import com.mcreatik.gallery.storage.PresignedUpload;
import com.mcreatik.gallery.storage.StorageArea;
import com.mcreatik.gallery.storage.StorageKeys;
import com.mcreatik.gallery.storage.StorageService;
import com.mcreatik.gallery.uploader.UploaderPrincipal;

/**
 * Upload protocol used by the desktop uploader. Every operation is idempotent so the uploader can
 * blindly retry after any network failure or restart.
 *
 * <p>Two modes, chosen per photo by the uploader:
 * <ul>
 *   <li><b>Prepared</b> (request carries {@code prepared}): the uploader resizes on the laptop and PUTs the web and
 *   thumbnail JPEGs straight to their public media keys; {@code complete} makes the photo READY immediately. Fast on
 *   small servers and weak venue uplinks; the original stays with the photographer.</li>
 *   <li><b>Original</b>: the uploader PUTs the original; the processing pipeline makes the derivatives.</li>
 * </ul>
 */
@Service
public class UploadService {

    /** Generous ceilings for what an uploader-prepared 2048 px web JPEG and 640 px thumbnail can weigh. */
    static final long MAX_PREPARED_WEB_BYTES = 8L * 1024 * 1024;
    static final long MAX_PREPARED_THUMB_BYTES = 2L * 1024 * 1024;
    private static final int MAX_DIMENSION = 30_000;

    private final PhotoRepository photos;
    private final EventRepository events;
    private final StorageService storage;
    private final GalleryProperties properties;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher publisher;

    public UploadService(PhotoRepository photos, EventRepository events, StorageService storage,
                         GalleryProperties properties, TransactionTemplate tx, ApplicationEventPublisher publisher) {
        this.photos = photos;
        this.events = events;
        this.storage = storage;
        this.properties = properties;
        this.tx = tx;
        this.publisher = publisher;
    }

    public enum SessionOutcome {
        /** PUT the file(s) to the returned URL(s), then call complete. */
        UPLOAD,
        /** This exact file is already in the event. Nothing to send. */
        DUPLICATE
    }

    /**
     * Web and thumbnail versions the uploader made itself. Width/height are the original's displayed dimensions
     * (after EXIF rotation), as the server pipeline would record them.
     */
    public record Prepared(long webSize, long thumbnailSize, int width, int height, Instant capturedAt) {
    }

    public record UploadSessionRequest(String fileName, long fileSize, String mimeType, String checksumSha256,
                                       Prepared prepared) {
        public UploadSessionRequest(String fileName, long fileSize, String mimeType, String checksumSha256) {
            this(fileName, fileSize, mimeType, checksumSha256, null);
        }
    }

    /**
     * {@code upload} is set in original mode; {@code webUpload} + {@code thumbnailUpload} in prepared mode.
     */
    public record UploadSessionResponse(SessionOutcome outcome, UUID photoId, PresignedUpload upload,
                                        PresignedUpload webUpload, PresignedUpload thumbnailUpload) {
        static UploadSessionResponse duplicate(UUID photoId) {
            return new UploadSessionResponse(SessionOutcome.DUPLICATE, photoId, null, null, null);
        }
    }

    public UploadSessionResponse createSession(UploaderPrincipal uploader, UploadSessionRequest request) {
        String mimeType = validate(request);
        String checksum = request.checksumSha256().toLowerCase(Locale.ROOT);
        String fileName = cleanFileName(request.fileName());

        Event event = events.findById(uploader.eventId()).orElseThrow(() -> ApiException.notFound("Event"));
        if (!event.getStatus().acceptsUploads()) {
            throw ApiException.conflict("EVENT_CLOSED", "This event is archived and no longer accepts photos");
        }

        var existing = photos.findByEventIdAndChecksumSha256(event.getId(), checksum);
        if (existing.isPresent()) {
            return resumeOrDuplicate(existing.get().getId(), uploader, fileName, request);
        }

        UUID photoId = UUID.randomUUID();
        Photo photo = new Photo(photoId, event.getId(), uploader.uploaderId(), fileName,
                StorageKeys.original(event.getId(), photoId, mimeType), request.fileSize(), mimeType, checksum);
        applyMode(photo, request.prepared());
        try {
            tx.executeWithoutResult(s -> photos.saveAndFlush(photo));
        } catch (DataIntegrityViolationException race) {
            // Another request inserted the same checksum concurrently: behave as if it had been there first.
            UUID winner = photos.findByEventIdAndChecksumSha256(event.getId(), checksum)
                    .orElseThrow(() -> race).getId();
            return resumeOrDuplicate(winner, uploader, fileName, request);
        }
        return uploadSession(photo, request.prepared());
    }

    private UploadSessionResponse resumeOrDuplicate(UUID photoId, UploaderPrincipal uploader, String fileName,
                                                    UploadSessionRequest request) {
        return tx.execute(s -> {
            Photo photo = photos.findById(photoId).orElseThrow(() -> ApiException.notFound("Photo"));
            switch (photo.getStatus()) {
                case UPLOADING -> {
                    // Interrupted earlier (crash, network): hand out fresh URLs for the same photo.
                }
                case FAILED -> photo.restartUpload(uploader.uploaderId(), fileName, request.fileSize());
                default -> {
                    return UploadSessionResponse.duplicate(photo.getId());
                }
            }
            // The retry may come from a different uploader version: follow whatever it sends now.
            applyMode(photo, request.prepared());
            return uploadSession(photo, request.prepared());
        });
    }

    private static void applyMode(Photo photo, Prepared prepared) {
        if (prepared == null) {
            photo.expectOriginal();
        } else {
            photo.prepareOnUploader(prepared.width(), prepared.height(), prepared.capturedAt(),
                    StorageKeys.optimized(photo.getEventId(), photo.getId()),
                    StorageKeys.thumbnail(photo.getEventId(), photo.getId()));
        }
    }

    private UploadSessionResponse uploadSession(Photo photo, Prepared prepared) {
        var ttl = properties.upload().presignTtl();
        if (prepared == null) {
            return new UploadSessionResponse(SessionOutcome.UPLOAD, photo.getId(),
                    storage.presignOriginalUpload(photo.getStorageKey(), photo.getMimeType(), photo.getFileSize(), ttl),
                    null, null);
        }
        return new UploadSessionResponse(SessionOutcome.UPLOAD, photo.getId(), null,
                storage.presignUpload(StorageArea.MEDIA, photo.getOptimizedStorageKey(), "image/jpeg",
                        prepared.webSize(), ttl),
                storage.presignUpload(StorageArea.MEDIA, photo.getThumbnailStorageKey(), "image/jpeg",
                        prepared.thumbnailSize(), ttl));
    }

    public record CompleteResponse(UUID photoId, PhotoStatus status) {
    }

    public CompleteResponse complete(UploaderPrincipal uploader, UUID photoId) {
        record Outcome(CompleteResponse response, Object event) {
        }
        Outcome outcome = tx.execute(s -> {
            Photo photo = photos.findByIdAndEventId(photoId, uploader.eventId())
                    .orElseThrow(() -> ApiException.notFound("Photo"));
            if (photo.getStatus() != PhotoStatus.UPLOADING) {
                return new Outcome(new CompleteResponse(photo.getId(), photo.getStatus()), null); // idempotent
            }
            Object event;
            if (photo.isOriginalStored()) {
                long stored = storage.size(StorageArea.ORIGINALS, photo.getStorageKey())
                        .orElseThrow(() -> ApiException.conflict("UPLOAD_NOT_FOUND", "File has not reached storage yet"));
                if (stored != photo.getFileSize()) {
                    throw ApiException.conflict("UPLOAD_INCOMPLETE",
                            "Stored size " + stored + " does not match declared size " + photo.getFileSize());
                }
                photo.markUploaded();
                event = new PhotoUploadedEvent(photo.getId());
            } else {
                // Presigned PUTs are signed for an exact length, so an object that exists is complete.
                if (storage.size(StorageArea.MEDIA, photo.getOptimizedStorageKey()).isEmpty()
                        || storage.size(StorageArea.MEDIA, photo.getThumbnailStorageKey()).isEmpty()) {
                    throw ApiException.conflict("UPLOAD_NOT_FOUND", "Web or thumbnail version has not reached storage yet");
                }
                photo.markReadyFromUploader();
                event = new PhotoReadyEvent(photo.getEventId(), photo.getId());
            }
            events.promoteToLive(photo.getEventId());
            return new Outcome(new CompleteResponse(photo.getId(), photo.getStatus()), event);
        });
        if (outcome.event() != null) {
            // After commit, so workers and guests only ever see committed rows.
            publisher.publishEvent(outcome.event());
        }
        return outcome.response();
    }

    private String validate(UploadSessionRequest r) {
        if (r.fileName() == null || r.fileName().isBlank()) {
            throw ApiException.badRequest("INVALID_FILE_NAME", "fileName is required");
        }
        if (r.fileSize() <= 0 || r.fileSize() > properties.upload().maxFileSizeBytes()) {
            throw ApiException.badRequest("FILE_TOO_LARGE",
                    "File size must be between 1 byte and " + properties.upload().maxFileSizeBytes() + " bytes");
        }
        if (r.checksumSha256() == null || !r.checksumSha256().matches("^[a-fA-F0-9]{64}$")) {
            throw ApiException.badRequest("INVALID_CHECKSUM", "checksumSha256 must be a hex SHA-256");
        }
        String mime = r.mimeType() == null ? "" : r.mimeType().toLowerCase(Locale.ROOT).trim();
        if (!properties.upload().allowedMimeTypes().contains(mime)) {
            throw ApiException.badRequest("UNSUPPORTED_TYPE", "Only JPEG and PNG photos are supported");
        }
        Prepared p = r.prepared();
        if (p != null) {
            if (p.webSize() <= 0 || p.webSize() > MAX_PREPARED_WEB_BYTES
                    || p.thumbnailSize() <= 0 || p.thumbnailSize() > MAX_PREPARED_THUMB_BYTES) {
                throw ApiException.badRequest("INVALID_PREPARED", "Prepared web/thumbnail sizes are out of range");
            }
            if (p.width() <= 0 || p.height() <= 0 || p.width() > MAX_DIMENSION || p.height() > MAX_DIMENSION) {
                throw ApiException.badRequest("INVALID_PREPARED", "Prepared photo dimensions are out of range");
            }
        }
        return mime;
    }

    static String cleanFileName(String name) {
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (base.isEmpty()) {
            base = "photo";
        }
        return base.length() > 255 ? base.substring(base.length() - 255) : base;
    }
}
