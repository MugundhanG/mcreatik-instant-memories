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

    /** Promotes an UPCOMING event to LIVE when the first photo arrives. */
    @Modifying
    @Query(value = "UPDATE events SET status = 'LIVE', updated_at = now() WHERE id = ?1 AND status = 'UPCOMING'",
            nativeQuery = true)
    int promoteToLive(UUID id);
}
