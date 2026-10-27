package com.mcreatik.gallery.photo;

import org.springframework.stereotype.Component;

import com.mcreatik.gallery.storage.StorageService;

@Component
public class PhotoMapper {

    private final StorageService storage;

    public PhotoMapper(StorageService storage) {
        this.storage = storage;
    }

    public PhotoDtos.PublicPhoto toPublic(Photo p) {
        return new PhotoDtos.PublicPhoto(p.getId(), url(p.getThumbnailStorageKey()), url(p.getOptimizedStorageKey()),
                p.getWidth(), p.getHeight(), p.getReadyAt(), new Cursor(p.getReadyAt(), p.getId()).encode());
    }

    public PhotoDtos.AdminPhoto toAdmin(Photo p) {
        return new PhotoDtos.AdminPhoto(p.getId(), p.getUploaderId(), p.getOriginalFileName(), p.getStatus(),
                p.getFailureReason(), p.getFileSize(), p.getWidth(), p.getHeight(),
                readyUrl(p, p.getThumbnailStorageKey()), readyUrl(p, p.getOptimizedStorageKey()),
                p.getCapturedAt(), p.getUploadedAt(), p.getReadyAt(), p.getCreatedAt(), p.isOriginalStored(),
                p.isHidden(), p.isLiked());
    }

    /** Uploader-prepared photos know their media keys before the files arrive: only link them once READY. */
    private String readyUrl(Photo p, String mediaKey) {
        return p.getStatus() == PhotoStatus.READY ? url(mediaKey) : null;
    }

    public String url(String mediaKey) {
        return mediaKey == null ? null : storage.publicMediaUrl(mediaKey);
    }
}
