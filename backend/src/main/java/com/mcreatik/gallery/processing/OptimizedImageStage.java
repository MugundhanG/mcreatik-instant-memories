package com.mcreatik.gallery.processing;

import java.awt.image.BufferedImage;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.mcreatik.gallery.config.GalleryProperties;
import com.mcreatik.gallery.storage.StorageArea;
import com.mcreatik.gallery.storage.StorageKeys;
import com.mcreatik.gallery.storage.StorageService;

/** Stage 3: full-screen web version (default 2048 px long edge) used by the viewer and for downloads. */
@Component
@Order(300)
public class OptimizedImageStage implements PhotoProcessingStage {

    private final StorageService storage;
    private final GalleryProperties properties;

    public OptimizedImageStage(StorageService storage, GalleryProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    @Override
    public void process(PhotoProcessingContext ctx) throws Exception {
        var p = properties.processing();
        BufferedImage web = ImageOps.fitWithin(ctx.decoded(), p.optimizedLongEdge());
        String key = StorageKeys.optimized(ctx.eventId(), ctx.photoId());
        storage.write(StorageArea.MEDIA, key, ImageOps.encodeJpeg(web, p.optimizedQuality()), "image/jpeg");
        ctx.optimizedKey(key);
        // Thumbnails are derived from the (already smaller) web image: faster and visually identical.
        ctx.decoded(web);
    }
}
