package com.mcreatik.gallery.uploader;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** When each camera was paused: a photo that arrived on the laptop inside such a window never reaches the gallery. */
@Component
public class UploaderPauseLog {

    private final JdbcTemplate jdbc;

    public UploaderPauseLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Opens a window (no-op when one is already open). */
    public void paused(UUID uploaderId, Instant at) {
        if (pausedSince(uploaderId) == null) {
            jdbc.update("insert into uploader_pause_windows (id, uploader_id, started_at) values (?, ?, ?)",
                    UUID.randomUUID(), uploaderId, Timestamp.from(at));
        }
    }

    /** Closes the open window, if any. */
    public void resumed(UUID uploaderId, Instant at) {
        jdbc.update("update uploader_pause_windows set ended_at = ? where uploader_id = ? and ended_at is null",
                Timestamp.from(at), uploaderId);
    }

    /** Start of the current pause, or null when the camera is not paused. */
    public Instant pausedSince(UUID uploaderId) {
        List<Timestamp> rows = jdbc.queryForList(
                "select started_at from uploader_pause_windows where uploader_id = ? and ended_at is null",
                Timestamp.class, uploaderId);
        return rows.isEmpty() ? null : rows.get(0).toInstant();
    }

    /** True when {@code at} falls inside any pause window of this camera. */
    public boolean wasPausedAt(UUID uploaderId, Instant at) {
        Integer n = jdbc.queryForObject("select count(*) from uploader_pause_windows where uploader_id = ?"
                + " and started_at <= ? and (ended_at is null or ? < ended_at)", Integer.class,
                uploaderId, Timestamp.from(at), Timestamp.from(at));
        return n != null && n > 0;
    }
}
