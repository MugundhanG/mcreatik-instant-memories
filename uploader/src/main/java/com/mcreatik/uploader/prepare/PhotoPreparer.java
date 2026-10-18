package com.mcreatik.uploader.prepare;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Makes the guest-facing versions of a photo on the laptop, exactly as the backend pipeline would: upright (EXIF
 * orientation applied), metadata stripped, a 2048 px web JPEG and a 640 px thumbnail. Uploading these (~1 MB in total)
 * instead of the 10+ MB original is what makes a photo appear within seconds on weak venue uplinks and small servers.
 */
public final class PhotoPreparer {

    /** Same values as the backend defaults (gallery.processing.*). */
    public static final int WEB_LONG_EDGE = 2048;
    public static final float WEB_QUALITY = 0.85f;
    public static final int THUMBNAIL_LONG_EDGE = 640;
    public static final float THUMBNAIL_QUALITY = 0.78f;

    private PhotoPreparer() {
    }

    /**
     * @param width  displayed width of the original (after EXIF rotation), as the gallery reports it
     * @param height displayed height of the original
     */
    public record Prepared(byte[] web, byte[] thumbnail, int width, int height, Instant capturedAt) {
    }

    public static Prepared prepare(Path original) throws IOException {
        byte[] bytes = Files.readAllBytes(original);
        ImageOps.ExifInfo exif = ImageOps.readExif(bytes);
        ImageOps.Decoded decoded = ImageOps.decode(bytes, WEB_LONG_EDGE);
        bytes = null; // the decoded image is all we need from here on

        boolean swap = ImageOps.swapsDimensions(exif.orientation());
        BufferedImage upright = ImageOps.applyOrientation(decoded.image(), exif.orientation());
        BufferedImage web = ImageOps.fitWithin(upright, WEB_LONG_EDGE);
        byte[] webJpeg = ImageOps.encodeJpeg(web, WEB_QUALITY);
        // Thumbnails come from the (already smaller) web image: faster and visually identical.
        byte[] thumbJpeg = ImageOps.encodeJpeg(ImageOps.fitWithin(web, THUMBNAIL_LONG_EDGE), THUMBNAIL_QUALITY);
        return new Prepared(webJpeg, thumbJpeg,
                swap ? decoded.sourceHeight() : decoded.sourceWidth(),
                swap ? decoded.sourceWidth() : decoded.sourceHeight(),
                exif.capturedAt());
    }
}
