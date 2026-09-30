package com.mcreatik.gallery.processing;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.mcreatik.gallery.config.GalleryProperties;

/** Stage 2: read EXIF (orientation, capture time), decode at reduced resolution, rotate upright. */
@Component
@Order(200)
public class DecodeStage implements PhotoProcessingStage {

    private final GalleryProperties properties;

    public DecodeStage(GalleryProperties properties) {
        this.properties = properties;
    }

    @Override
    public void process(PhotoProcessingContext ctx) throws Exception {
        ImageOps.ExifInfo exif = ImageOps.readExif(ctx.original());
        ctx.exifOrientation(exif.orientation());
        ctx.capturedAt(exif.capturedAt());

        ImageOps.Decoded decoded;
        try {
            decoded = ImageOps.decode(ctx.original(), properties.processing().optimizedLongEdge());
        } catch (Exception e) {
            throw new PhotoProcessingException("Could not decode image: " + e.getMessage(), e);
        }
        boolean swap = ImageOps.swapsDimensions(exif.orientation());
        ctx.dimensions(swap ? decoded.sourceHeight() : decoded.sourceWidth(),
                swap ? decoded.sourceWidth() : decoded.sourceHeight());
        ctx.decoded(ImageOps.applyOrientation(decoded.image(), exif.orientation()));
        ctx.releaseOriginal();
    }
}
