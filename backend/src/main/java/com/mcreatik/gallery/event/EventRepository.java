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

    List<Event> findByRetentionUntilBefore(LocalDate date);

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
