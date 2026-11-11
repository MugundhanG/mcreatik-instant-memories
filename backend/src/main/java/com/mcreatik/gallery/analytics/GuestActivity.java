package com.mcreatik.gallery.analytics;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What guests do on a public gallery, as counted on the studio's analytics. */
public final class GuestActivity {

    private GuestActivity() {
    }

    public enum Type {
        /** Gallery opened (once per browser tab session); {@code source} says from a QR scan or a shared link. */
        VISIT,
        PHOTO_VIEW,
        DOWNLOAD,
        SHARE,
        /** Tapped a "Follow on Instagram" link. */
        INSTAGRAM,
        /** Tapped the studio's WhatsApp or call button (a booking lead). */
        CONTACT,
        /** Liked a photo (once per guest and photo); UNLIKE takes it back and is not stored. */
        LIKE,
        UNLIKE
    }

    public record Totals(long people, long qrScans, long visits, long photoViews, long downloads, long downloaders,
                         long shares, long instagramClicks, long contacts, long likes) {
    }

    /** One hour (Asia/Kolkata wall clock) with activity. */
    public record Hour(String hour, long people, long photoViews, long downloads) {
    }

    public record TopPhoto(UUID photoId, String thumbnailUrl, String fileName, long views, long downloads, long likes) {
    }

    /**
     * {@code topPhotos}: by downloads, then views (the older single list). {@code mostLiked}, {@code mostDownloaded}
     * and {@code mostViewed}: the photos ranked each way, without the ones at zero.
     */
    public record Summary(Totals totals, List<Hour> hours, List<TopPhoto> topPhotos, Instant lastActivityAt,
                          List<TopPhoto> mostLiked, List<TopPhoto> mostDownloaded, List<TopPhoto> mostViewed) {
    }
}
