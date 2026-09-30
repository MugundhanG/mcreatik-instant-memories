package com.mcreatik.gallery.processing;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.mcreatik.gallery.common.Tokens;
import com.mcreatik.gallery.storage.StorageArea;
import com.mcreatik.gallery.storage.StorageService;

/** Stage 1: the stored bytes are exactly what the uploader announced, and really are an image. */
@Component
@Order(100)
public class ValidateStage implements PhotoProcessingStage {

    private final StorageService storage;

    public ValidateStage(StorageService storage) {
        this.storage = storage;
    }

    @Override
    public void process(PhotoProcessingContext ctx) throws Exception {
        byte[] bytes = storage.read(StorageArea.ORIGINALS, ctx.photo().getStorageKey());
        if (bytes.length != ctx.photo().getFileSize()) {
            throw new PhotoProcessingException("Stored file size does not match the announced size");
        }
        if (!Tokens.sha256Hex(bytes).equals(ctx.photo().getChecksumSha256())) {
            throw new PhotoProcessingException("Checksum mismatch: the file was corrupted in transit");
        }
        String mime = ImageOps.sniffMimeType(bytes);
        if (mime == null) {
            throw new PhotoProcessingException("Not a JPEG or PNG image");
        }
        ctx.original(bytes);
        ctx.detectedMimeType(mime);
    }
}
