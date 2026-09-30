package com.mcreatik.gallery.event;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mcreatik.gallery.auth.CurrentAdmin;

@RestController
@RequestMapping("/api/admin/events")
public class AdminEventController {

    private final EventService events;

    public AdminEventController(EventService events) {
        this.events = events;
    }

    @GetMapping
    public List<EventDtos.EventSummary> list(Authentication auth) {
        return events.list(CurrentAdmin.id(auth));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventDtos.EventDetail create(Authentication auth, @Valid @RequestBody EventDtos.CreateEventRequest request) {
        return events.create(CurrentAdmin.id(auth), request);
    }

    @GetMapping("/{eventId}")
    public EventDtos.EventDetail get(Authentication auth, @PathVariable UUID eventId) {
        return events.get(CurrentAdmin.id(auth), eventId);
    }

    @PatchMapping("/{eventId}")
    public EventDtos.EventDetail update(Authentication auth, @PathVariable UUID eventId,
                                        @Valid @RequestBody EventDtos.UpdateEventRequest request) {
        return events.update(CurrentAdmin.id(auth), eventId, request);
    }

    @DeleteMapping("/{eventId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth, @PathVariable UUID eventId) {
        events.delete(CurrentAdmin.id(auth), eventId);
    }

    @GetMapping("/{eventId}/stats")
    public EventDtos.EventStats stats(Authentication auth, @PathVariable UUID eventId) {
        return events.stats(CurrentAdmin.id(auth), eventId);
    }
}
