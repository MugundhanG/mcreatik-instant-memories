package com.mcreatik.gallery.event;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mcreatik.gallery.config.GalleryProperties;

/**
 * Privacy retention: events past their retention date are permanently deleted (database rows and all files).
 * Default retention is event date + 90 days; admins can extend or clear it per event.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final EventService events;
    private final GalleryProperties properties;

    public RetentionJob(EventService events, GalleryProperties properties) {
        this.events = events;
        this.properties = properties;
    }

    @Scheduled(cron = "0 17 3 * * *", zone = "UTC")
    public void purgeExpired() {
        if (!properties.retention().purgeEnabled()) {
            return;
        }
        for (Event event : events.expiredEvents(LocalDate.now(ZoneOffset.UTC))) {
            try {
                events.deleteExpired(event);
                log.info("Retention: deleted event {} ({}), retention ended {}", event.getId(), event.getSlug(),
                        event.getRetentionUntil());
            } catch (Exception e) {
                log.error("Retention: failed to delete event {}", event.getId(), e);
            }
        }
    }
}
