package com.mcreatik.gallery.uploader;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mcreatik.gallery.auth.CurrentAdmin;

@RestController
@RequestMapping("/api/admin")
public class AdminUploaderController {

    private final UploaderAdminService service;

    public AdminUploaderController(UploaderAdminService service) {
        this.service = service;
    }

    public record UploaderNameRequest(@NotBlank @Size(max = 120) String name) {
    }

    @PostMapping("/events/{eventId}/uploaders")
    @ResponseStatus(HttpStatus.CREATED)
    public UploaderAdminService.UploaderCredentials create(Authentication auth, @PathVariable UUID eventId,
                                                           @Valid @RequestBody UploaderNameRequest request) {
        return service.create(CurrentAdmin.id(auth), eventId, request.name());
    }

    @PostMapping("/uploaders/{uploaderId}/rotate-token")
    public UploaderAdminService.UploaderCredentials rotate(Authentication auth, @PathVariable UUID uploaderId) {
        return service.rotateToken(CurrentAdmin.id(auth), uploaderId);
    }

    @PatchMapping("/uploaders/{uploaderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rename(Authentication auth, @PathVariable UUID uploaderId,
                       @Valid @RequestBody UploaderNameRequest request) {
        service.rename(CurrentAdmin.id(auth), uploaderId, request.name());
    }

    @DeleteMapping("/uploaders/{uploaderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth, @PathVariable UUID uploaderId) {
        service.delete(CurrentAdmin.id(auth), uploaderId);
    }
}
