package com.mcreatik.uploader.queue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Crash-safe local queue in SQLite. Every photo the uploader has seen is recorded here before any network
 * activity, so a closed laptop, a crash or a dead venue router never loses a photograph: on restart the queue
 * simply continues.
 */
public class UploadQueue implements AutoCloseable {

    public enum EnqueueResult { ADDED, ALREADY_QUEUED }

    private final Connection connection;
    private final LongSupplier clock;

    public UploadQueue(Path databaseFile) {
        this(databaseFile, System::currentTimeMillis);
    }

    public UploadQueue(Path databaseFile, LongSupplier clock) {
        this.clock = clock;
        try {
            Files.createDirectories(databaseFile.toAbsolutePath().getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=FULL"); // survive power loss, not just app crashes
                s.execute("PRAGMA busy_timeout=5000");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS uploads (
                            id              INTEGER PRIMARY KEY AUTOINCREMENT,
                            path            TEXT    NOT NULL,
                            file_name       TEXT    NOT NULL,
                            size            INTEGER NOT NULL,
                            modified_ms     INTEGER NOT NULL,
                            sha256          TEXT    NOT NULL UNIQUE,
                            mime            TEXT    NOT NULL,
                            status          TEXT    NOT NULL,
                            attempts        INTEGER NOT NULL DEFAULT 0,
                            next_attempt_at INTEGER NOT NULL DEFAULT 0,
                            photo_id        TEXT,
                            last_error      TEXT,
                            created_at      INTEGER NOT NULL,
                            updated_at      INTEGER NOT NULL
                        )""");
                s.execute("CREATE INDEX IF NOT EXISTS ix_uploads_path ON uploads (path, size, modified_ms)");
                s.execute("CREATE INDEX IF NOT EXISTS ix_uploads_due ON uploads (status, next_attempt_at, id)");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot open upload queue at " + databaseFile + ": " + e.getMessage(), e);
        }
    }

    /** Cheap pre-check so unchanged files are not re-hashed on every rescan/restart. */
    public synchronized boolean isKnown(Path path, long size, long modifiedMillis) {
        return query("SELECT 1 FROM uploads WHERE path = ? AND size = ? AND modified_ms = ?", ps -> {
            ps.setString(1, path.toString());
            ps.setLong(2, size);
            ps.setLong(3, modifiedMillis);
        }, rs -> true).isPresent();
    }

    public synchronized EnqueueResult enqueue(Path path, String fileName, long size, long modifiedMillis,
                                              String sha256, String mimeType) {
        long now = clock.getAsLong();
        int inserted = update("""
                INSERT OR IGNORE INTO uploads (path, file_name, size, modified_ms, sha256, mime, status,
                                               attempts, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', 0, 0, ?, ?)""", ps -> {
            ps.setString(1, path.toString());
            ps.setString(2, fileName);
            ps.setLong(3, size);
            ps.setLong(4, modifiedMillis);
            ps.setString(5, sha256);
            ps.setString(6, mimeType);
            ps.setLong(7, now);
            ps.setLong(8, now);
        });
        return inserted == 1 ? EnqueueResult.ADDED : EnqueueResult.ALREADY_QUEUED;
    }

    /** Atomically takes the oldest due item and marks it UPLOADING. */
    public synchronized Optional<QueueItem> claimNext() {
        long now = clock.getAsLong();
        Optional<QueueItem> item = query("""
                SELECT * FROM uploads WHERE status = 'QUEUED' AND next_attempt_at <= ?
                ORDER BY id LIMIT 1""", ps -> ps.setLong(1, now), UploadQueue::map);
        item.ifPresent(i -> update("""
                UPDATE uploads SET status = 'UPLOADING', attempts = attempts + 1, updated_at = ? WHERE id = ?""", ps -> {
            ps.setLong(1, now);
            ps.setLong(2, i.id());
        }));
        return item.flatMap(i -> find(i.id()));
    }

    public synchronized void markDone(long id, String photoId, boolean duplicate) {
        update("UPDATE uploads SET status = ?, photo_id = ?, last_error = NULL, updated_at = ? WHERE id = ?", ps -> {
            ps.setString(1, duplicate ? QueueStatus.DUPLICATE.name() : QueueStatus.DONE.name());
            ps.setString(2, photoId);
            ps.setLong(3, clock.getAsLong());
            ps.setLong(4, id);
        });
    }

    /** Transient problem (network, server busy): back to the queue, due again at {@code nextAttemptAt}. */
    public synchronized void markRetry(long id, String error, long nextAttemptAt) {
        update("""
                UPDATE uploads SET status = 'QUEUED', last_error = ?, next_attempt_at = ?, updated_at = ?
                WHERE id = ?""", ps -> {
            ps.setString(1, truncate(error));
            ps.setLong(2, nextAttemptAt);
            ps.setLong(3, clock.getAsLong());
            ps.setLong(4, id);
        });
    }

    public synchronized void markFailed(long id, String error) {
        update("UPDATE uploads SET status = 'FAILED', last_error = ?, updated_at = ? WHERE id = ?", ps -> {
            ps.setString(1, truncate(error));
            ps.setLong(2, clock.getAsLong());
            ps.setLong(3, id);
        });
    }

    /** On startup: anything that was mid-upload when the app stopped goes back to the queue. */
    public synchronized int resetInFlight() {
        return update("UPDATE uploads SET status = 'QUEUED', next_attempt_at = 0 WHERE status = 'UPLOADING'", ps -> { });
    }

    /** Photographer pressed "Retry failed". */
    public synchronized int retryFailed() {
        return update("""
                UPDATE uploads SET status = 'QUEUED', attempts = 0, next_attempt_at = 0, updated_at = ?
                WHERE status = 'FAILED'""", ps -> ps.setLong(1, clock.getAsLong()));
    }

    /** Connectivity came back: don't wait out long backoffs, retry everything now. */
    public synchronized int expediteRetries() {
        long now = clock.getAsLong();
        return update("UPDATE uploads SET next_attempt_at = ? WHERE status = 'QUEUED' AND next_attempt_at > ?", ps -> {
            ps.setLong(1, now);
            ps.setLong(2, now);
        });
    }

    public synchronized QueueCounts counts() {
        int[] c = new int[QueueStatus.values().length];
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT status, count(*) FROM uploads GROUP BY status")) {
            while (rs.next()) {
                c[QueueStatus.valueOf(rs.getString(1)).ordinal()] = rs.getInt(2);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return new QueueCounts(c[QueueStatus.QUEUED.ordinal()], c[QueueStatus.UPLOADING.ordinal()],
                c[QueueStatus.DONE.ordinal()], c[QueueStatus.DUPLICATE.ordinal()], c[QueueStatus.FAILED.ordinal()]);
    }

    public synchronized List<QueueItem> recent(int limit) {
        List<QueueItem> items = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM uploads ORDER BY updated_at DESC, id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(map(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return items;
    }

    public synchronized Optional<QueueItem> find(long id) {
        return query("SELECT * FROM uploads WHERE id = ?", ps -> ps.setLong(1, id), UploadQueue::map);
    }

    /** Earliest future retry time, if any item is waiting on a backoff. */
    public synchronized Optional<Long> nextDueAt() {
        return query("SELECT min(next_attempt_at) FROM uploads WHERE status = 'QUEUED'", ps -> { },
                rs -> rs.getObject(1) == null ? null : rs.getLong(1));
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // closing anyway
        }
    }

    private static QueueItem map(ResultSet rs) throws SQLException {
        return new QueueItem(rs.getLong("id"), Path.of(rs.getString("path")), rs.getString("file_name"),
                rs.getLong("size"), rs.getLong("modified_ms"), rs.getString("sha256"), rs.getString("mime"),
                QueueStatus.valueOf(rs.getString("status")), rs.getInt("attempts"), rs.getLong("next_attempt_at"),
                rs.getString("photo_id"), rs.getString("last_error"), rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 1000 ? s.substring(0, 1000) : s;
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    @FunctionalInterface
    private interface Mapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private int update(String sql, Binder binder) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Queue update failed: " + e.getMessage(), e);
        }
    }

    private <T> Optional<T> query(String sql, Binder binder, Mapper<T> mapper) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(mapper.map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Queue query failed: " + e.getMessage(), e);
        }
    }
}
