package com.mcreatik.gallery.photo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PhotoRepository extends JpaRepository<Photo, UUID> {

    Optional<Photo> findByEventIdAndChecksumSha256(UUID eventId, String checksumSha256);

    Optional<Photo> findByIdAndEventId(UUID id, UUID eventId);

    // ---- Guest feed (newest first, keyset pagination on (ready_at, id)) ----

    @Query("""
            select p from Photo p where p.eventId = ?1 and p.status = com.mcreatik.gallery.photo.PhotoStatus.READY
            order by p.readyAt desc, p.id desc""")
    List<Photo> findLatestReady(UUID eventId, Limit limit);

    @Query("""
            select p from Photo p where p.eventId = ?1 and p.status = com.mcreatik.gallery.photo.PhotoStatus.READY
              and (p.readyAt < ?2 or (p.readyAt = ?2 and p.id < ?3))
            order by p.readyAt desc, p.id desc""")
    List<Photo> findReadyBefore(UUID eventId, Instant readyAt, UUID id, Limit limit);

    /** Catch-up after an SSE reconnect: everything newer than the client's last cursor, oldest first. */
    @Query("""
            select p from Photo p where p.eventId = ?1 and p.status = com.mcreatik.gallery.photo.PhotoStatus.READY
              and (p.readyAt > ?2 or (p.readyAt = ?2 and p.id > ?3))
            order by p.readyAt asc, p.id asc""")
    List<Photo> findReadyAfter(UUID eventId, Instant readyAt, UUID id, Limit limit);

    // ---- Admin ----

    @Query("select p from Photo p where p.eventId = ?1 order by p.createdAt desc, p.id desc")
    List<Photo> findForAdmin(UUID eventId, Limit limit);

    @Query("""
            select p from Photo p where p.eventId = ?1 and (p.createdAt < ?2 or (p.createdAt = ?2 and p.id < ?3))
            order by p.createdAt desc, p.id desc""")
    List<Photo> findForAdminBefore(UUID eventId, Instant createdAt, UUID id, Limit limit);

    @Query(value = """
            SELECT status, count(*) AS photos, coalesce(sum(file_size), 0) AS bytes
            FROM photos WHERE event_id = ?1 GROUP BY status""", nativeQuery = true)
    List<Object[]> countByStatus(UUID eventId);

    @Query(value = """
            SELECT uploader_id, count(*) FILTER (WHERE status = 'READY') AS ready,
                   count(*) AS total, max(created_at) AS last_upload
            FROM photos WHERE event_id = ?1 GROUP BY uploader_id""", nativeQuery = true)
    List<Object[]> countByUploader(UUID eventId);

    @Query(value = """
            SELECT event_id, count(*) FROM photos
            WHERE event_id IN (?1) AND status = 'READY' GROUP BY event_id""", nativeQuery = true)
    List<Object[]> countReadyByEvent(List<UUID> eventIds);

    List<Photo> findByEventIdAndStatusOrderByReadyAtDesc(UUID eventId, PhotoStatus status, Limit limit);

    // ---- Processing queue (the photos table is the queue) ----

    /** Requeues photos whose worker died mid-processing (e.g. deploy/restart). */
    @Modifying
    @Query(value = """
            UPDATE photos SET status = 'UPLOADED', updated_at = now()
            WHERE status = 'PROCESSING' AND processing_started_at < ?1""", nativeQuery = true)
    int requeueStale(Instant startedBefore);
}
