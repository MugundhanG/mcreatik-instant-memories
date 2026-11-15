package com.mcreatik.gallery.face;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mcreatik.gallery.auth.AdminAccess;
import com.mcreatik.gallery.auth.CurrentAdmin;
import com.mcreatik.gallery.billing.BillingService;
import com.mcreatik.gallery.common.ApiException;
import com.mcreatik.gallery.event.Event;
import com.mcreatik.gallery.event.EventService;
import com.mcreatik.gallery.event.EventType;

/** The dashboard's Face search card: its state and indexing progress; McreatiK can switch it on or off (free trials). */
@RestController
@RequestMapping("/api/admin/events/{eventId}/face-search")
public class FaceSearchAdminController {

    /** {@code available}: face search is switched on for the whole system; {@code indexed} of {@code total} guest photos. */
    public record FaceSearchState(boolean enabled, boolean available, long indexed, long total, int mainPrice, int quickPrice) {
    }

    public record SetRequest(Boolean enabled) {
    }

    private final EventService events;
    private final FaceSearchService faces;
    private final BillingService billing;
    private final AdminAccess access;

    public FaceSearchAdminController(EventService events, FaceSearchService faces, BillingService billing, AdminAccess access) {
        this.events = events;
        this.faces = faces;
        this.billing = billing;
        this.access = access;
    }

    @GetMapping
    public FaceSearchState get(Authentication auth, @PathVariable UUID eventId) {
        return state(events.owned(CurrentAdmin.id(auth), eventId));
    }

    @PutMapping
    public FaceSearchState set(Authentication auth, @PathVariable UUID eventId, @RequestBody SetRequest request) {
        access.requireSuperAdmin(auth);
        if (request.enabled() == null) {
            throw ApiException.badRequest("INVALID_REQUEST", "Say whether face search is on");
        }
        Event event = events.owned(CurrentAdmin.id(auth), eventId);
        if (request.enabled()) {
            if (event.getEventType() == EventType.CLIENT) {
                throw ApiException.badRequest("NOT_AVAILABLE", "Face search is for guest galleries (Main and Quick events)");
            }
            faces.enable(eventId);
        } else {
            faces.disable(eventId);
        }
        return state(events.owned(CurrentAdmin.id(auth), eventId));
    }

    private FaceSearchState state(Event event) {
        FaceSearchService.Status s = faces.status(event);
        BillingService.Settings prices = billing.settings();
        return new FaceSearchState(s.enabled(), s.available(), s.indexed(), s.total(), prices.faceSearchMainPrice(),
                prices.faceSearchQuickPrice());
    }
}
