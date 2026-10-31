package com.mcreatik.gallery.analytics;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mcreatik.gallery.auth.CurrentAdmin;
import com.mcreatik.gallery.event.EventService;

@RestController
public class GuestActivityController {

    private final GuestActivityService activity;
    private final EventService events;

    public GuestActivityController(GuestActivityService activity, EventService events) {
        this.activity = activity;
        this.events = events;
    }

    public record ActivityBatch(String visitorId, List<GuestActivityService.Item> items) {
    }

    /** Guests' browsers report what they did, in batches. Anonymous; no login. */
    @PostMapping("/api/public/events/{slug}/activity")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void record(@PathVariable String slug, @RequestBody ActivityBatch batch) {
        activity.record(events.publicEvent(slug).getId(), batch.visitorId(), batch.items());
    }

    @GetMapping("/api/admin/events/{eventId}/analytics")
    public GuestActivity.Summary summary(Authentication auth, @PathVariable UUID eventId) {
        events.owned(CurrentAdmin.id(auth), eventId);
        return activity.summary(eventId);
    }
}
