package com.mcreatik.uploader.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mcreatik.uploader.api.ApiModels.StudioEvent;

class EventListTest {

    private static StudioEvent event(String name, String date, String status, String created) {
        return new StudioEvent(name.toLowerCase().replace(' ', '-'), name, date, status, created);
    }

    private final List<StudioEvent> all = List.of(
            event("Kavya's 30th Birthday", "2026-03-02", "COMPLETED", "2026-01-10T00:00:00Z"),
            event("Arun & Priya Wedding", "2026-12-12", "UPCOMING", "2026-02-01T00:00:00Z"),
            event("Old Closed Event", "2027-01-01", "ARCHIVED", "2026-02-05T00:00:00Z"),
            event("Housewarming", null, "DRAFT", "2026-05-01T00:00:00Z"),
            event("Reception Night", "2026-12-12", "LIVE", "2026-04-01T00:00:00Z"));

    @Test
    void archivedEventsAreHiddenAndTheRestAreNewestFirst() {
        assertThat(EventList.visible(all, "")).extracting(StudioEvent::name).containsExactly(
                "Reception Night", "Arun & Priya Wedding", "Kavya's 30th Birthday", "Housewarming");
    }

    @Test
    void searchFiltersByNameIgnoringCase() {
        assertThat(EventList.visible(all, "  WEDDING ")).extracting(StudioEvent::name)
                .containsExactly("Arun & Priya Wedding");
        assertThat(EventList.visible(all, "zzz")).isEmpty();
        assertThat(EventList.visible(all, "closed")).isEmpty(); // archived stays hidden even when searched
        assertThat(EventList.visible(all, null)).hasSize(4);
    }

    @Test
    void statusLabelsAreFriendly() {
        assertThat(EventList.statusLabel("LIVE")).isEqualTo("Live");
        assertThat(EventList.statusLabel("UPCOMING")).isEqualTo("Upcoming");
        assertThat(EventList.statusLabel("COMPLETED")).isEqualTo("Completed");
        assertThat(EventList.statusLabel("DRAFT")).isEqualTo("Draft");
    }
}
