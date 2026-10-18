package com.mcreatik.uploader.api;

import java.util.Map;

/** JSON shapes of the McreatiK uploader API (see backend UploaderApiController). */
public final class ApiModels {

    private ApiModels() {
    }

    public record Me(String uploaderId, String uploaderName, String eventId, String eventName, String eventStatus,
                     String galleryUrl, long heartbeatIntervalSeconds, long maxFileSizeBytes) {
    }

    public record HeartbeatRequest(String deviceIdentifier, int queuePending, int queueFailed, String lastError,
                                   String appVersion) {
    }

    public record HeartbeatResponse(String eventStatus, boolean acceptingUploads) {
    }

    /** {@code prepared} is null when sending the original for server-side processing. */
    public record CreateUploadRequest(String fileName, long fileSize, String mimeType, String checksumSha256,
                                      PreparedVersions prepared) {
        public CreateUploadRequest(String fileName, long fileSize, String mimeType, String checksumSha256) {
            this(fileName, fileSize, mimeType, checksumSha256, null);
        }
    }

    /** Web and thumbnail versions made on the laptop. Width/height/capturedAt describe the original. */
    public record PreparedVersions(long webSize, long thumbnailSize, int width, int height, String capturedAt) {
    }

    public record PresignedUpload(String url, String method, Map<String, String> headers, String expiresAt) {
    }

    /**
     * outcome = UPLOAD (send the bytes) or DUPLICATE (already in the event). A server that accepted the prepared
     * versions returns {@code webUpload} + {@code thumbnailUpload}; otherwise (or an older server) {@code upload}
     * for the original.
     */
    public record UploadSession(String outcome, String photoId, PresignedUpload upload, PresignedUpload webUpload,
                                PresignedUpload thumbnailUpload) {
        public boolean acceptsPrepared() {
            return webUpload != null && thumbnailUpload != null;
        }

        public boolean isDuplicate() {
            return "DUPLICATE".equals(outcome);
        }
    }

    public record CompleteResponse(String photoId, String status) {
    }

    public record ErrorBody(String code, String message) {
    }
}
