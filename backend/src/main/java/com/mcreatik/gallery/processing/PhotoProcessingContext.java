package com.mcreatik.gallery.processing;

import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.UUID;

import com.mcreatik.gallery.photo.Photo;

/** Mutable state passed through the pipeline for one photo. */
public class PhotoProcessingContext {

    private final Photo photo;
    private byte[] original;
    private String detectedMimeType;
    private int exifOrientation = 1;
    private Instant capturedAt;
    private int width;
    private int height;
    private BufferedImage decoded;
    private String optimizedKey;
    private String thumbnailKey;

    public PhotoProcessingContext(Photo photo) {
        this.photo = photo;
    }

    public Photo photo() {
        return photo;
    }

    public UUID photoId() {
        return photo.getId();
    }

    public UUID eventId() {
        return photo.getEventId();
    }

    public byte[] original() {
        return original;
    }

    public void original(byte[] original) {
        this.original = original;
    }

    public String detectedMimeType() {
        return detectedMimeType;
    }

    public void detectedMimeType(String detectedMimeType) {
        this.detectedMimeType = detectedMimeType;
    }

    public int exifOrientation() {
        return exifOrientation;
    }

    public void exifOrientation(int exifOrientation) {
        this.exifOrientation = exifOrientation;
    }

    public Instant capturedAt() {
        return capturedAt;
    }

    public void capturedAt(Instant capturedAt) {
        this.capturedAt = capturedAt;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Dimensions of the original as displayed (after EXIF rotation). */
    public void dimensions(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public BufferedImage decoded() {
        return decoded;
    }

    public void decoded(BufferedImage decoded) {
        this.decoded = decoded;
    }

    public String optimizedKey() {
        return optimizedKey;
    }

    public void optimizedKey(String optimizedKey) {
        this.optimizedKey = optimizedKey;
    }

    public String thumbnailKey() {
        return thumbnailKey;
    }

    public void thumbnailKey(String thumbnailKey) {
        this.thumbnailKey = thumbnailKey;
    }

    /** Drops large buffers as soon as they are no longer needed. */
    public void releaseOriginal() {
        this.original = null;
    }

    public void releaseDecoded() {
        this.decoded = null;
    }
}
