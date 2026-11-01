package com.mcreatik.gallery.download;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.mcreatik.gallery.common.ApiException;
import com.mcreatik.gallery.common.Tokens;
import com.mcreatik.gallery.storage.StorageArea;
import com.mcreatik.gallery.storage.StorageService;

/**
 * Many photos as one ZIP file: the studio's "download selected" and the couple's "download my album".
 * <p>
 * A logged-in request prepares a short-lived link; opening the link streams the ZIP straight from storage, one photo
 * at a time, so memory stays flat however big the album is and phones get a single file. Originals are included
 * when the studio kept them, else the gallery version. Links live in memory (single server) for 30 minutes.
 */
@Service
public class ZipDownloads {

    public static final int MAX_PHOTOS = 1000;
    static final Duration TTL = Duration.ofMinutes(30);
    /** ZIPs streaming at the same time: keeps bandwidth for the live galleries. */
    static final int MAX_STREAMS = 3;

    private static final Logger log = LoggerFactory.getLogger(ZipDownloads.class);

    record Job(UUID eventId, List<UUID> photoIds, boolean includeHidden, String fileName, Instant expiresAt) {
    }

    record Item(String fileName, String storageKey, String webKey, boolean originalStored) {
    }

    public record Prepared(String path, int photos, String fileName) {
    }

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Semaphore streams = new Semaphore(MAX_STREAMS);
    private final JdbcTemplate jdbc;
    private final StorageService storage;

    public ZipDownloads(JdbcTemplate jdbc, StorageService storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    /** {@code includeHidden}: the studio may download photos hidden from guests; the couple may not. */
    public Prepared prepare(UUID eventId, List<UUID> photoIds, boolean includeHidden, String name) {
        List<UUID> ids = photoIds == null ? List.of() : photoIds.stream().distinct().toList();
        if (ids.isEmpty()) {
            throw ApiException.badRequest("NOTHING_SELECTED", "Select at least one photo");
        }
        if (ids.size() > MAX_PHOTOS) {
            throw ApiException.badRequest("TOO_MANY",
                    "Download at most " + MAX_PHOTOS + " photos at a time. Pick a category or fewer photos.");
        }
        int found = items(eventId, ids, includeHidden).size();
        if (found == 0) {
            throw ApiException.notFound("Photos");
        }
        String fileName = fileName(name);
        String token = Tokens.randomToken("zip_");
        jobs.put(token, new Job(eventId, ids, includeHidden, fileName, Instant.now().plus(TTL)));
        return new Prepared("/api/public/downloads/" + token, found, fileName);
    }

    Job job(String token) {
        Job job = token == null ? null : jobs.get(token);
        if (job == null || job.expiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.GONE, "LINK_EXPIRED", "This download link has expired. Start the download again.");
        }
        return job;
    }

    /** Takes one of the few streaming slots, or tells the browser to try again shortly. */
    void acquire() {
        if (!streams.tryAcquire()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BUSY",
                    "Other downloads are running. Please try again in a minute.");
        }
    }

    void release() {
        streams.release();
    }

    /** Photos in the order the day happened, with their stored files. */
    List<Item> items(UUID eventId, List<UUID> ids, boolean includeHidden) {
        return jdbc.query("""
                SELECT original_file_name, storage_key, optimized_storage_key, original_stored
                FROM photos
                WHERE event_id = ? AND id = ANY (?) AND status = 'READY'%s
                ORDER BY coalesce(captured_at, created_at), id""".formatted(includeHidden ? "" : " AND NOT hidden"),
                ps -> {
                    ps.setObject(1, eventId);
                    ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids.toArray()));
                },
                (rs, n) -> new Item(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)));
    }

    void write(Job job, OutputStream out) throws IOException {
        List<Item> items = items(job.eventId(), job.photoIds(), job.includeHidden());
        Set<String> used = new HashSet<>();
        int missing = 0;
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            // Photos are already compressed: storing them as they are keeps the small server's CPU free.
            zip.setLevel(Deflater.NO_COMPRESSION);
            for (Item item : items) {
                byte[] data = null;
                boolean original = false;
                if (item.originalStored()) {
                    data = tryRead(StorageArea.ORIGINALS, item.storageKey());
                    original = data != null;
                }
                if (data == null && item.webKey() != null) {
                    data = tryRead(StorageArea.MEDIA, item.webKey());
                }
                if (data == null) {
                    missing++;
                    continue;
                }
                zip.putNextEntry(new ZipEntry(unique(entryName(item.fileName(), original), used)));
                zip.write(data);
                zip.closeEntry();
            }
            if (missing > 0) {
                zip.putNextEntry(new ZipEntry("NOTE.txt"));
                zip.write((missing + " photo(s) could not be added. Please ask your studio.\n").getBytes());
                zip.closeEntry();
            }
        }
    }

    private byte[] tryRead(StorageArea area, String key) {
        try {
            return storage.read(area, key);
        } catch (RuntimeException e) {
            log.warn("ZIP download: could not read {} {}: {}", area, key, e.toString());
            return null;
        }
    }

    /** The camera's file name; a gallery version is a JPEG whatever the original was. */
    static String entryName(String original, boolean isOriginal) {
        String name = original == null ? "" : original.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_").trim();
        if (name.isEmpty() || name.startsWith(".")) {
            name = "photo" + name;
        }
        if (!isOriginal && !name.toLowerCase(Locale.ROOT).matches(".*\\.jpe?g$")) {
            int dot = name.lastIndexOf('.');
            name = (dot > 0 ? name.substring(0, dot) : name) + ".jpg";
        }
        return name;
    }

    /** "IMG_1.jpg", "IMG_1 (2).jpg", ... when two cameras used the same file name. */
    static String unique(String name, Set<String> used) {
        String candidate = name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; !used.add(candidate.toLowerCase(Locale.ROOT)); i++) {
            candidate = base + " (" + i + ")" + ext;
        }
        return candidate;
    }

    static String fileName(String name) {
        String base = name == null ? "" : name.replaceAll("[^A-Za-z0-9 _.&-]+", "").trim().replaceAll("\\s+", "-");
        if (base.length() > 80) {
            base = base.substring(0, 80);
        }
        return (base.isEmpty() ? "photos" : base) + ".zip";
    }

    @Scheduled(fixedDelay = 600_000)
    public void evictExpired() {
        Instant now = Instant.now();
        jobs.values().removeIf(j -> j.expiresAt().isBefore(now));
    }
}
