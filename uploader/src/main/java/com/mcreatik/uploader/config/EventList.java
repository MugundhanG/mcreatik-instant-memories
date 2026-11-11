package com.mcreatik.uploader.config;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.mcreatik.uploader.api.ApiModels.StudioEvent;

/** Which events the photographer can pick, and in what order. */
public final class EventList {

    private EventList() {
    }

    /** Archived (closed or deleted) events are hidden; the rest are filtered by name and listed newest first. */
    public static List<StudioEvent> visible(List<StudioEvent> all, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return all.stream()
                .filter(e -> !"ARCHIVED".equals(e.status()))
                .filter(e -> q.isEmpty() || (e.name() != null && e.name().toLowerCase(Locale.ROOT).contains(q)))
                .sorted(Comparator.comparing(EventList::sortKey).reversed())
                .toList();
    }

    /** Event date first (ISO dates sort as text), then creation time; events without a date go last. */
    private static String sortKey(StudioEvent e) {
        return (e.eventDate() == null ? "0000-00-00" : e.eventDate()) + "|" + (e.createdAt() == null ? "" : e.createdAt());
    }

    public static String statusLabel(String status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case "LIVE" -> "Live";
            case "UPCOMING" -> "Upcoming";
            case "COMPLETED" -> "Completed";
            case "DRAFT" -> "Draft";
            default -> status;
        };
    }
}
