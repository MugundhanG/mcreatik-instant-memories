package com.mcreatik.gallery.uploader;

import java.time.Duration;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mcreatik.gallery.common.ApiException;
import com.mcreatik.gallery.config.GalleryProperties;
import com.mcreatik.gallery.event.EventRepository;
import com.mcreatik.gallery.event.EventStatus;
import com.mcreatik.gallery.photo.UploadService;

/** API used by the McreatiK Uploader desktop app. */
@RestController
@RequestMapping("/api/uploader")
public class UploaderApiController {

    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final UploaderRepository uploaders;
    private final EventRepository events;
    private final UploadService uploadService;
    private final GalleryProperties properties;

    public UploaderApiController(UploaderRepository uploaders, EventRepository events, UploadService uploadService,
                                 GalleryProperties properties) {
        this.uploaders = uploaders;
        this.events = events;
        this.uploadService = uploadService;
        this.properties = properties;
    }

    public record MeResponse(UUID uploaderId, String uploaderName, UUID eventId, String eventName,
                             EventStatus eventStatus, String galleryUrl, long heartbeatIntervalSeconds,
                             long maxFileSizeBytes) {
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal UploaderPrincipal principal) {
        Uploader uploader = uploaders.findById(principal.uploaderId()).orElseThrow(() -> ApiException.notFound("Uploader"));
        var event = events.findById(principal.eventId()).orElseThrow(() -> ApiException.notFound("Event"));
        return new MeResponse(uploader.getId(), uploader.getName(), event.getId(), event.getName(), event.getStatus(),
                properties.galleryUrl(event.getSlug()), HEARTBEAT_INTERVAL.toSeconds(),
                properties.upload().maxFileSizeBytes());
    }

    public record HeartbeatRequest(@Size(max = 200) String deviceIdentifier, @Min(0) int queuePending,
                                   @Min(0) int queueFailed, @Size(max = 2000) String lastError,
                                   @Size(max = 40) String appVersion) {
    }

    public record HeartbeatResponse(EventStatus eventStatus, boolean acceptingUploads) {
    }

    @PostMapping("/heartbeat")
    @Transactional
    public HeartbeatResponse heartbeat(@AuthenticationPrincipal UploaderPrincipal principal,
                                       @Valid @RequestBody HeartbeatRequest request) {
        Uploader uploader = uploaders.findById(principal.uploaderId()).orElseThrow(() -> ApiException.notFound("Uploader"));
        uploader.recordHeartbeat(request.deviceIdentifier(), request.queuePending(), request.queueFailed(),
                request.lastError());
        var event = events.findById(principal.eventId()).orElseThrow(() -> ApiException.notFound("Event"));
        return new HeartbeatResponse(event.getStatus(), event.getStatus().acceptsUploads());
    }

    public record CreateUploadRequest(@NotBlank @Size(max = 1024) String fileName,
                                      @Positive long fileSize,
                                      @NotBlank @Size(max = 50) String mimeType,
                                      @NotNull @Size(min = 64, max = 64) String checksumSha256) {
    }

    @PostMapping("/uploads")
    public UploadService.UploadSessionResponse createUpload(@AuthenticationPrincipal UploaderPrincipal principal,
                                                            @Valid @RequestBody CreateUploadRequest request) {
        return uploadService.createSession(principal, new UploadService.UploadSessionRequest(
                request.fileName(), request.fileSize(), request.mimeType(), request.checksumSha256()));
    }

    @PostMapping("/uploads/{photoId}/complete")
    public UploadService.CompleteResponse complete(@AuthenticationPrincipal UploaderPrincipal principal,
                                                   @PathVariable UUID photoId) {
        return uploadService.complete(principal, photoId);
    }
}
