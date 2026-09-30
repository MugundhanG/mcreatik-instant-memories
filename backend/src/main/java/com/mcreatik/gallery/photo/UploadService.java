package com.mcreatik.gallery.photo;

import java.util.Locale;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
 */
@Service
public class UploadService {

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
        /** PUT the file to the returned URL, then call complete. */
        UPLOAD,
        /** This exact file is already in the event. Nothing to send. */
        DUPLICATE
    }

    public record UploadSessionRequest(String fileName, long fileSize, String mimeType, String checksumSha256) {
    }

    public record UploadSessionResponse(SessionOutcome outcome, UUID photoId, PresignedUpload upload) {
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
            return resumeOrDuplicate(existing.get().getId(), uploader, fileName, request.fileSize());
        }

        UUID photoId = UUID.randomUUID();
        String key = StorageKeys.original(event.getId(), photoId, mimeType);
        try {
            tx.executeWithoutResult(s -> photos.saveAndFlush(new Photo(photoId, event.getId(), uploader.uploaderId(),
                    fileName, key, request.fileSize(), mimeType, checksum)));
        } catch (DataIntegrityViolationException race) {
            // Another request inserted the same checksum concurrently: behave as if it had been there first.
            UUID winner = photos.findByEventIdAndChecksumSha256(event.getId(), checksum)
                    .orElseThrow(() -> race).getId();
            return resumeOrDuplicate(winner, uploader, fileName, request.fileSize());
        }
        return new UploadSessionResponse(SessionOutcome.UPLOAD, photoId, presign(key, mimeType, request.fileSize()));
    }

    private UploadSessionResponse resumeOrDuplicate(UUID photoId, UploaderPrincipal uploader, String fileName, long fileSize) {
        return tx.execute(s -> {
            Photo photo = photos.findById(photoId).orElseThrow(() -> ApiException.notFound("Photo"));
            switch (photo.getStatus()) {
                case UPLOADING -> {
                    // Interrupted earlier (crash, network): hand out a fresh URL for the same photo.
                }
                case FAILED -> photo.restartUpload(uploader.uploaderId(), fileName, fileSize);
                default -> {
                    return new UploadSessionResponse(SessionOutcome.DUPLICATE, photo.getId(), null);
                }
            }
            return new UploadSessionResponse(SessionOutcome.UPLOAD, photo.getId(),
                    presign(photo.getStorageKey(), photo.getMimeType(), photo.getFileSize()));
        });
    }

    private PresignedUpload presign(String key, String mimeType, long size) {
        return storage.presignOriginalUpload(key, mimeType, size, properties.upload().presignTtl());
    }

    public record CompleteResponse(UUID photoId, PhotoStatus status) {
    }

    @Transactional
    public CompleteResponse complete(UploaderPrincipal uploader, UUID photoId) {
        Photo photo = photos.findByIdAndEventId(photoId, uploader.eventId())
                .orElseThrow(() -> ApiException.notFound("Photo"));
        if (photo.getStatus() != PhotoStatus.UPLOADING) {
            return new CompleteResponse(photo.getId(), photo.getStatus()); // idempotent
        }
        long stored = storage.size(StorageArea.ORIGINALS, photo.getStorageKey())
                .orElseThrow(() -> ApiException.conflict("UPLOAD_NOT_FOUND", "File has not reached storage yet"));
        if (stored != photo.getFileSize()) {
            throw ApiException.conflict("UPLOAD_INCOMPLETE",
                    "Stored size " + stored + " does not match declared size " + photo.getFileSize());
        }
        photo.markUploaded();
        events.promoteToLive(photo.getEventId());
        publisher.publishEvent(new PhotoUploadedEvent(photo.getId()));
        return new CompleteResponse(photo.getId(), photo.getStatus());
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
