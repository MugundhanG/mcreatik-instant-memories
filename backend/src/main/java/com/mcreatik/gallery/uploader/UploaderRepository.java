package com.mcreatik.gallery.uploader;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface UploaderRepository extends JpaRepository<Uploader, UUID> {

    Optional<Uploader> findByTokenHash(String tokenHash);

    List<Uploader> findByEventIdOrderByCreatedAtAsc(UUID eventId);

    /** Cheap presence update, throttled to one write per uploader every 5 seconds. */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE uploaders SET last_seen_at = now()
            WHERE id = ?1 AND (last_seen_at IS NULL OR last_seen_at < now() - interval '5 seconds')
            """, nativeQuery = true)
    int touch(UUID id);
}
