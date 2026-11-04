package com.mcreatik.uploader.ui;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.mcreatik.uploader.engine.EngineStatus;
import com.mcreatik.uploader.queue.QueueCounts;
import com.mcreatik.uploader.source.FtpPhotoSource;

/**
 * What the window shows, worked out only from the uploader's real state: the engine's connection and queue, and the
 * camera FTP server. Nothing here is simulated, so nothing turns green before a real photo or connection.
 * Pure and unit-tested; the window just paints it.
 */
public record LiveState(Conn conn, Tone tone, String title, String detail,
                        String camera, String autoUpload, String lastPhoto, String speed, String duration,
                        List<Stage> pipeline, int uploaded, int total) {

    public enum Conn { ONLINE, RECONNECTING, OFFLINE, ERROR }

    /** Colour families from the theme tokens (green, blue, grey, amber, red). */
    public enum Tone {
        GREEN("green"), BLUE("blue"), GREY("grey"), AMBER("amber"), RED("red");

        public final String token;

        Tone(String token) {
            this.token = token;
        }
    }

    public record Stage(String name, String icon, Tone tone, String status, String detail) {
    }

    /** OFFLINE for this long is shown as Offline instead of Reconnecting (it keeps retrying either way). */
    static final Duration RECONNECT_GRACE = Duration.ofMinutes(2);

    /**
     * @param ftp         the camera FTP server's status, or null when the camera writes into the folder itself
     * @param lastPhotoAt when the newest photo of this event arrived, or null before the first
     */
    public static LiveState of(EngineStatus s, FtpPhotoSource.Status ftp, Instant lastPhotoAt, Instant now) {
        QueueCounts c = s.counts();
        Conn conn;
        Tone tone;
        String title;
        String detail;
        boolean ftpBroken = ftp != null && ftp.error() != null;
        if (s.connection() == EngineStatus.Connection.BLOCKED) {
            conn = Conn.ERROR;
            tone = Tone.RED;
            title = "Uploading paused";
            detail = s.blockedReason() == null ? "McreatiK stopped this uploader." : s.blockedReason();
        } else if (ftpBroken) {
            conn = Conn.ERROR;
            tone = Tone.RED;
            title = "The camera can't send photos";
            detail = ftp.error();
        } else if (s.connection() == EngineStatus.Connection.ONLINE) {
            conn = Conn.ONLINE;
            tone = Tone.GREEN;
            title = "Online — photos upload automatically";
            detail = c.pending() > 0 ? "Uploading the latest photos…"
                    : c.failed() > 0 ? count(c.failed(), "photo") + " couldn't be uploaded. Press Retry failed."
                    : ftp != null ? "Waiting for new photos from the camera." : "Waiting for new photos in the folder.";
        } else if (s.connection() == EngineStatus.Connection.CONNECTING) {
            conn = Conn.RECONNECTING;
            tone = Tone.BLUE;
            title = "Connecting to McreatiK…";
            detail = "Photos are kept safely on this computer meanwhile.";
        } else if (s.offlineSince() == null || Duration.between(s.offlineSince(), now).compareTo(RECONNECT_GRACE) < 0) {
            conn = Conn.RECONNECTING;
            tone = Tone.BLUE;
            title = "Reconnecting…";
            detail = "The internet dropped. Photos are safely queued and upload by themselves.";
        } else {
            conn = Conn.OFFLINE;
            tone = Tone.AMBER;
            title = "Offline — photos are safely queued";
            detail = "Check the hotspot's mobile data. Uploading resumes by itself when it's back.";
        }

        String camera;
        if (ftp == null) {
            camera = "Watching the folder";
        } else if (ftpBroken) {
            camera = "Not receiving";
        } else if (ftp.filesReceived() > 0) {
            camera = String.format(Locale.ROOT, "%,d received this session", ftp.filesReceived());
        } else {
            camera = "No photo yet";
        }

        String autoUpload = switch (conn) {
            case ONLINE -> c.pending() > 0 ? "Uploading " + count(c.pending(), "photo") : "On · up to date";
            case RECONNECTING, OFFLINE -> c.pending() > 0 ? "Paused · " + count(c.pending(), "photo") + " waiting"
                    : "Paused until online";
            case ERROR -> s.connection() == EngineStatus.Connection.BLOCKED ? "Paused" : "On";
        };

        String lastPhoto = lastPhotoAt == null ? "None yet" : ago(lastPhotoAt, now);
        String speed = s.bytesPerSecond() > 0 ? speed(s.bytesPerSecond()) : "—";
        String duration = s.onlineSince() != null ? "Online for " + span(s.onlineSince(), now)
                : s.offlineSince() != null ? "Offline for " + span(s.offlineSince(), now) : "—";

        List<Stage> pipeline = List.of(cameraStage(ftp, lastPhotoAt, now), computerStage(c), serverStage(s, conn),
                galleryStage(c));
        return new LiveState(conn, tone, title, detail, camera, autoUpload, lastPhoto, speed, duration, pipeline,
                c.done() + c.duplicate(), c.total());
    }

    private static Stage cameraStage(FtpPhotoSource.Status ftp, Instant lastPhotoAt, Instant now) {
        if (ftp == null) {
            return lastPhotoAt == null ? new Stage("Camera folder", "camera", Tone.GREY, "Waiting", "No photo in the folder yet")
                    : new Stage("Camera folder", "camera", Tone.GREEN, "Receiving", "Last photo " + ago(lastPhotoAt, now));
        }
        if (ftp.error() != null) {
            return new Stage("Camera", "camera", Tone.RED, "Not receiving", "Wi-Fi receiver stopped");
        }
        if (ftp.filesReceived() == 0) {
            return new Stage("Camera", "camera", Tone.GREY, "Waiting", "Same Wi-Fi as this computer");
        }
        return new Stage("Camera", "camera", Tone.GREEN, "Receiving",
                ftp.filesReceived() + " this session · " + (ftp.lastFileAt() == null ? "" : ago(ftp.lastFileAt(), now)));
    }

    private static Stage computerStage(QueueCounts c) {
        if (c.total() == 0) {
            return new Stage("Computer", "monitor", Tone.GREY, "Waiting", "No photos yet");
        }
        if (c.pending() > 0) {
            return new Stage("Computer", "monitor", Tone.BLUE, "Queued", count(c.pending(), "photo") + " to upload");
        }
        return new Stage("Computer", "monitor", Tone.GREEN, "Saved", "Kept on this computer");
    }

    private static Stage serverStage(EngineStatus s, Conn conn) {
        QueueCounts c = s.counts();
        return switch (conn) {
            case ERROR -> s.connection() == EngineStatus.Connection.BLOCKED
                    ? new Stage("Upload server", "cloud-upload", Tone.RED, "Paused", "See the message above")
                    : s.connection() == EngineStatus.Connection.ONLINE
                    ? new Stage("Upload server", "cloud-upload", Tone.GREEN, "Connected", "McreatiK is reachable")
                    : new Stage("Upload server", "cloud-upload", Tone.RED, "Offline", "Retrying automatically");
            case RECONNECTING -> s.connection() == EngineStatus.Connection.CONNECTING
                    ? new Stage("Upload server", "cloud-upload", Tone.GREY, "Connecting", "Reaching McreatiK…")
                    : new Stage("Upload server", "cloud-upload", Tone.RED, "Offline", "Reconnecting automatically");
            case OFFLINE -> new Stage("Upload server", "cloud-upload", Tone.RED, "Offline", "Retrying automatically");
            case ONLINE -> c.uploading() > 0 || !s.active().isEmpty()
                    ? new Stage("Upload server", "cloud-upload", Tone.BLUE, "Uploading",
                    s.bytesPerSecond() > 0 ? speed(s.bytesPerSecond()) : count(Math.max(1, c.uploading()), "photo") + " in progress")
                    : c.failed() > 0
                    ? new Stage("Upload server", "cloud-upload", Tone.RED, count(c.failed(), "failed photo"), "Press Retry failed")
                    : new Stage("Upload server", "cloud-upload", Tone.GREEN, "Connected", "Up to date");
        };
    }

    private static Stage galleryStage(QueueCounts c) {
        int live = c.done() + c.duplicate();
        if (live == 0) {
            return new Stage("Live gallery", "images", Tone.GREY, "Waiting", "Nothing published yet");
        }
        return new Stage("Live gallery", "images", Tone.GREEN, "Live", count(live, "photo") + " from this camera");
    }

    static String count(int n, String noun) {
        return String.format(Locale.ROOT, "%,d %s%s", n, noun, n == 1 ? "" : "s");
    }

    static String ago(Instant then, Instant now) {
        long s = Math.max(0, Duration.between(then, now).toSeconds());
        if (s < 5) {
            return "just now";
        }
        if (s < 60) {
            return s + " s ago";
        }
        if (s < 3600) {
            return s / 60 + " min ago";
        }
        return s / 3600 + " h " + (s % 3600) / 60 + " min ago";
    }

    static String span(Instant since, Instant now) {
        long s = Math.max(0, Duration.between(since, now).toSeconds());
        if (s < 60) {
            return s + " s";
        }
        if (s < 3600) {
            return s / 60 + " min";
        }
        return s / 3600 + " h " + (s % 3600) / 60 + " min";
    }

    static String speed(long bytesPerSecond) {
        if (bytesPerSecond >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / 1_000_000.0);
        }
        return String.format(Locale.ROOT, "%d KB/s", Math.max(1, bytesPerSecond / 1_000));
    }

    public static String size(long bytes) {
        if (bytes >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0);
        }
        return String.format(Locale.ROOT, "%d KB", Math.max(1, bytes / 1_000));
    }
}
