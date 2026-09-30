package com.mcreatik.uploader.api;

/**
 * Classifies every failure so the engine knows what to do with the photo:
 * retry later, give up on this file, or pause everything until a human fixes the setup.
 */
public class ApiException extends Exception {

    public enum Kind {
        /** Network down, timeout, 5xx, 429, expired upload URL. Keep the photo queued and retry. */
        TRANSIENT,
        /** This file can never be accepted (bad type, too large). Mark it failed. */
        PERMANENT,
        /** Token revoked / event archived. Keep everything queued and alert the photographer. */
        BLOCKED
    }

    private final Kind kind;
    private final int status;
    private final String code;

    public ApiException(Kind kind, int status, String code, String message) {
        super(message);
        this.kind = kind;
        this.status = status;
        this.code = code;
    }

    public ApiException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.status = 0;
        this.code = null;
    }

    public Kind kind() {
        return kind;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
