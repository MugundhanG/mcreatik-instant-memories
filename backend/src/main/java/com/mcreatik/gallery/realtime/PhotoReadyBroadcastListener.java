package com.mcreatik.gallery.realtime;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.mcreatik.gallery.photo.PhotoMapper;
import com.mcreatik.gallery.photo.PhotoReadyEvent;
import com.mcreatik.gallery.photo.PhotoRepository;

/** Bridges the processing pipeline to connected guests: PHOTO_READY. */
@Component
public class PhotoReadyBroadcastListener {

    private final PhotoRepository photos;
    private final PhotoMapper mapper;
    private final GalleryBroadcaster broadcaster;

    public PhotoReadyBroadcastListener(PhotoRepository photos, PhotoMapper mapper, GalleryBroadcaster broadcaster) {
        this.photos = photos;
        this.mapper = mapper;
        this.broadcaster = broadcaster;
    }

    @EventListener
    public void onPhotoReady(PhotoReadyEvent event) {
        photos.findById(event.photoId()).ifPresent(p -> broadcaster.photoReady(event.eventId(), mapper.toPublic(p)));
    }
}
