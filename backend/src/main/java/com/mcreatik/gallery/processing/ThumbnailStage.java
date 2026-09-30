package com.mcreatik.gallery.processing;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.mcreatik.gallery.config.GalleryProperties;
import com.mcreatik.gallery.storage.StorageArea;
import com.mcreatik.gallery.storage.StorageKeys;
import com.mcreatik.gallery.storage.StorageService;

/** Stage 4: grid thumbnail (default 640 px long edge ≈ sharp 2-column grid on a 3x phone screen). */
@Component
@Order(400)
public class ThumbnailStage implements PhotoProcessingStage {

    private final StorageService storage;
    private final GalleryProperties properties;

    public ThumbnailStage(StorageService storage, GalleryProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    @Override
    public void process(PhotoProcessingContext ctx) throws Exception {
        var p = properties.processing();
        var thumb = ImageOps.fitWithin(ctx.decoded(), p.thumbnailLongEdge());
        String key = StorageKeys.thumbnail(ctx.eventId(), ctx.photoId());
        storage.write(StorageArea.MEDIA, key, ImageOps.encodeJpeg(thumb, p.thumbnailQuality()), "image/jpeg");
        ctx.thumbnailKey(key);
        ctx.releaseDecoded();
    }
}
