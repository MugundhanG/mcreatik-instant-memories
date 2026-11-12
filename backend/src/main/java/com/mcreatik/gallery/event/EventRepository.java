package com.mcreatik.gallery.event;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EventRepository extends JpaRepository<Event, UUID> {

    Optional<Event> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<Event> findByOwnerIdOrderByEventDateDescCreatedAtDesc(UUID ownerId);

    Optional<Event> findByIdAndOwnerId(UUID id, UUID ownerId);

    @Query(value = "SELECT * FROM events WHERE retention_until < ?1 AND photos_deleted_at IS NULL", nativeQuery = true)
    List<Event> findExpiredWithPhotos(LocalDate date);

    /** Retention, step 1: the event is archived and its galleries closed; the row, payments and charges stay. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE events SET status = 'ARCHIVED', live_gallery_enabled = false, client_gallery_enabled = false,
                   cover_photo_id = NULL, photos_deleted_at = now(), updated_at = now() WHERE id = ?1""",
            nativeQuery = true)
    int archive(UUID id);

    /** Retention, step 2: every photo row (the album picks go with them by cascade). */
    @Modifying
    @Query(value = "DELETE FROM photos WHERE event_id = ?1", nativeQuery = true)
    int deletePhotos(UUID eventId);

    List<Event> findAllByOrderByEventDateDescCreatedAtDesc();

    /** Per owner: [owner_id, events, ready photos, stored bytes] for the super admin's Studios page. */
    @Query(value = """
            SELECT e.owner_id, count(DISTINCT e.id),
                   count(p.id) FILTER (WHERE p.status = 'READY'),
                   coalesce(sum(p.media_bytes + CASE WHEN p.original_stored THEN p.file_size ELSE 0 END), 0)
            FROM events e LEFT JOIN photos p ON p.event_id = e.id
            GROUP BY e.owner_id""", nativeQuery = true)
    List<Object[]> usageByOwner();

    /** Promotes an UPCOMING event to LIVE when the first photo arrives. */
    @Modifying
    @Query(value = "UPDATE events SET status = 'LIVE', updated_at = now() WHERE id = ?1 AND status = 'UPCOMING'",
            nativeQuery = true)
    int promoteToLive(UUID id);
}
