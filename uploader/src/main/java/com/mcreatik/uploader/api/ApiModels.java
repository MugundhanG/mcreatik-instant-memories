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

    public record CreateUploadRequest(String fileName, long fileSize, String mimeType, String checksumSha256) {
    }

    public record PresignedUpload(String url, String method, Map<String, String> headers, String expiresAt) {
    }

    /** outcome = UPLOAD (send the bytes) or DUPLICATE (already in the event). */
    public record UploadSession(String outcome, String photoId, PresignedUpload upload) {
        public boolean isDuplicate() {
            return "DUPLICATE".equals(outcome);
        }
    }

    public record CompleteResponse(String photoId, String status) {
    }

    public record ErrorBody(String code, String message) {
    }
}
