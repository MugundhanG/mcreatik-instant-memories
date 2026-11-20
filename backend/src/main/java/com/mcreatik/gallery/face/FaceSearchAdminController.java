package com.mcreatik.gallery.face;

import java.util.UUID;

import org.springframework.http.HttpStatus;
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

/**
 * The dashboard's Face search card (Settings): its state and indexing progress. The studio switches it on or off for an
 * event it paid for; McreatiK can switch it on for any event (free trials). The private gallery needs face search on.
 */
@RestController
@RequestMapping("/api/admin/events/{eventId}/face-search")
public class FaceSearchAdminController {

    /** {@code available}: face search is switched on for the whole system; {@code indexed} of {@code total} guest photos. */
    /** {@code paid}: bought for this event (the studio may switch it); {@code privateGallery}: guests see only their photos. */
    public record FaceSearchState(boolean enabled, boolean available, long indexed, long total, int mainPrice, int quickPrice,
                                  boolean paid, boolean privateGallery) {
    }

    /** Either or both: {@code enabled} (face search) and {@code privateGallery}. */
    public record SetRequest(Boolean enabled, Boolean privateGallery) {
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
        if (request.enabled() == null && request.privateGallery() == null) {
            throw ApiException.badRequest("INVALID_REQUEST", "Say whether face search or the private gallery is on");
        }
        Event event = events.owned(CurrentAdmin.id(auth), eventId);
        boolean superAdmin = access.isSuperAdmin(CurrentAdmin.id(auth));
        if (request.enabled() != null && request.enabled() != event.isFaceSearchEnabled()) {
            if (!superAdmin && !event.isFaceSearchPaid()) {
                throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "FACE_SEARCH_NOT_PAID", "Add face search to this event first");
            }
            if (request.enabled()) {
                if (event.getEventType() == EventType.CLIENT) {
                    throw ApiException.badRequest("NOT_AVAILABLE", "Face search is for guest galleries (Main and Quick events)");
                }
                faces.enable(eventId);
            } else {
                faces.disable(eventId);
            }
        }
        if (request.privateGallery() != null) {
            boolean on = request.privateGallery();
            if (on && !(request.enabled() != null ? request.enabled() : event.isFaceSearchEnabled())) {
                throw ApiException.badRequest("FACE_SEARCH_OFF", "Turn on face search first: the private gallery needs it");
            }
            faces.setPrivate(eventId, on);
        }
        return state(events.owned(CurrentAdmin.id(auth), eventId));
    }

    private FaceSearchState state(Event event) {
        FaceSearchService.Status s = faces.status(event);
        BillingService.Settings prices = billing.settings();
        return new FaceSearchState(s.enabled(), s.available(), s.indexed(), s.total(), prices.faceSearchMainPrice(),
                prices.faceSearchQuickPrice(), event.isFaceSearchPaid(), s.enabled() && event.isFacePrivate());
    }
}
