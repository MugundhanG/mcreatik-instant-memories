package com.mcreatik.gallery.processing;

/** A permanent processing failure (corrupt file, unsupported format). Not retried. */
public class PhotoProcessingException extends Exception {

    public PhotoProcessingException(String message) {
        super(message);
    }

    public PhotoProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
