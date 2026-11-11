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
    private final HqRequests hqRequests;

    public GuestActivityController(GuestActivityService activity, EventService events, HqRequests hqRequests) {
        this.hqRequests = hqRequests;
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

    public record HqRequestBody(UUID photoId, String name, String contact, String visitorId) {
    }

    /** A guest asks for the full-quality photo and leaves an email or WhatsApp number. No login. */
    @PostMapping("/api/public/events/{slug}/hq-requests")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requestHq(@PathVariable String slug, @RequestBody HqRequestBody body) {
        hqRequests.create(events.publicEvent(slug).getId(), body.photoId(), body.name(), body.contact(), body.visitorId());
    }

    @GetMapping("/api/admin/events/{eventId}/hq-requests")
    public List<HqRequests.Request> hqRequests(Authentication auth, @PathVariable UUID eventId) {
        events.owned(CurrentAdmin.id(auth), eventId);
        return hqRequests.list(eventId);
    }

    public record DoneBody(boolean done) {
    }

    @org.springframework.web.bind.annotation.PatchMapping("/api/admin/events/{eventId}/hq-requests/{requestId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void hqDone(Authentication auth, @PathVariable UUID eventId, @PathVariable UUID requestId, @RequestBody DoneBody body) {
        events.owned(CurrentAdmin.id(auth), eventId);
        hqRequests.setDone(eventId, requestId, body.done());
    }
}
