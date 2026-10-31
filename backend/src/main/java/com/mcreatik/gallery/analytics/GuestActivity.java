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
        INSTAGRAM
    }

    public record Totals(long people, long qrScans, long visits, long photoViews, long downloads, long downloaders,
                         long shares, long instagramClicks) {
    }

    /** One hour (Asia/Kolkata wall clock) with activity. */
    public record Hour(String hour, long people, long photoViews, long downloads) {
    }

    public record TopPhoto(UUID photoId, String thumbnailUrl, String fileName, long views, long downloads) {
    }

    public record Summary(Totals totals, List<Hour> hours, List<TopPhoto> topPhotos, Instant lastActivityAt) {
    }
}
