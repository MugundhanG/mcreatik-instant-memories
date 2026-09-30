package com.mcreatik.gallery.storage;

public enum StorageArea {
    /** Private. Original camera files. Never publicly readable. */
    ORIGINALS,
    /** Public via CDN. Resized web images and thumbnails only, under unguessable keys. */
    MEDIA
}
