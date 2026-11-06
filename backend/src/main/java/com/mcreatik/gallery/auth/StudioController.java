package com.mcreatik.gallery.auth;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.mcreatik.gallery.billing.BillingService;
import com.mcreatik.gallery.common.ApiException;
import com.mcreatik.gallery.event.EventRepository;

/** Super admin only: client studio logins, with their usage for billing. */
@RestController
@RequestMapping("/api/admin/studios")
public class StudioController {

    private final BillingService billing;
    private final StudioBranding branding;

    private final AdminUserRepository users;
    private final AdminAccess access;
    private final PasswordEncoder passwordEncoder;
    private final EventRepository events;

    public StudioController(AdminUserRepository users, AdminAccess access, PasswordEncoder passwordEncoder,
                            EventRepository events, BillingService billing, StudioBranding branding) {
        this.billing = billing;
        this.branding = branding;
        this.users = users;
        this.access = access;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
    }

    public record Studio(UUID id, String name, String email, AdminRole role, boolean disabled,
                         boolean mustChangePassword, Instant createdAt, long events, long photos, long storageBytes,
                         StudioBranding.Brand brand) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<Studio> list(Authentication auth) {
        access.requireSuperAdmin(auth);
        Map<UUID, long[]> usage = new HashMap<>();
        for (Object[] row : events.usageByOwner()) {
            usage.put((UUID) row[0], new long[] {((Number) row[1]).longValue(), ((Number) row[2]).longValue(),
                    ((Number) row[3]).longValue()});
        }
        return users.findAllByOrderByCreatedAtAsc().stream().map(u -> {
            long[] n = usage.getOrDefault(u.getId(), new long[3]);
            return new Studio(u.getId(), u.getDisplayName(), u.getEmail(), u.getRole(), u.isDisabled(),
                    u.isMustChangePassword(), u.getCreatedAt(), n[0], n[1], n[2], branding.of(u));
        }).toList();
    }

    public record CreateStudioRequest(@NotBlank @Size(max = 120) String name, @NotBlank @Email @Size(max = 320) String email,
                                      @NotBlank @Size(min = AccountController.MIN_PASSWORD, max = 200) String password,
                                      BillingService.Plan plan) {
    }

    /** The studio logs in with this email and temporary password, then must choose its own password. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Studio create(Authentication auth, @Valid @RequestBody CreateStudioRequest request) {
        access.requireSuperAdmin(auth);
        if (users.findByEmail(request.email().trim()).isPresent()) {
            throw ApiException.conflict("EMAIL_TAKEN", "A login with this email already exists");
        }
        AdminUser studio = new AdminUser(request.email(), passwordEncoder.encode(request.password()),
                request.name().trim(), AdminRole.STUDIO);
        studio.setPassword(studio.getPasswordHash(), true);
        users.saveAndFlush(studio);
        if (request.plan() != null) {
            billing.updateStudio(studio.getId(), request.plan(), null, null);
        }
        return new Studio(studio.getId(), studio.getDisplayName(), studio.getEmail(), studio.getRole(), false, true,
                studio.getCreatedAt(), 0, 0, 0, branding.of(studio));
    }

    /** Only the fields present change. {@code newPassword}: a new temporary password (forgotten password). */
    public record UpdateStudioRequest(@Size(max = 120) String name, Boolean disabled,
                                      @Size(min = AccountController.MIN_PASSWORD, max = 200) String newPassword) {
    }

    @PatchMapping("/{studioId}")
    @Transactional
    public Studio update(Authentication auth, @PathVariable UUID studioId, @Valid @RequestBody UpdateStudioRequest request) {
        AdminUser me = access.requireSuperAdmin(auth);
        AdminUser studio = users.findById(studioId).orElseThrow(() -> ApiException.notFound("Studio"));
        if (request.name() != null && !request.name().isBlank()) {
            studio.rename(request.name().trim());
        }
        if (request.disabled() != null) {
            if (studio.getId().equals(me.getId())) {
                throw ApiException.badRequest("CANNOT_DISABLE_SELF", "You cannot disable your own login");
            }
            studio.setDisabled(request.disabled());
        }
        if (request.newPassword() != null) {
            studio.setPassword(passwordEncoder.encode(request.newPassword()), true);
        }
        return new Studio(studio.getId(), studio.getDisplayName(), studio.getEmail(), studio.getRole(),
                studio.isDisabled(), studio.isMustChangePassword(), studio.getCreatedAt(), 0, 0, 0, branding.of(studio));
    }

    // ---- McreatiK sets up a studio's branding (logo, name, Instagram, poster logo size) during onboarding ----

    public record BrandingRequest(String displayName, String instagram, Integer logoScale, String boardTheme,
                                  String phone) {
    }

    @PatchMapping("/{studioId}/branding")
    @Transactional
    public StudioBranding.Brand editBranding(Authentication auth, @PathVariable UUID studioId,
                                             @RequestBody BrandingRequest request) {
        AdminUser studio = studio(auth, studioId);
        branding.edit(studio, request.displayName(), request.instagram(), request.logoScale(), request.boardTheme(),
                request.phone());
        return branding.of(studio);
    }

    @PostMapping(value = "/{studioId}/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public StudioBranding.Brand uploadLogo(Authentication auth, @PathVariable UUID studioId,
                                           @RequestPart("logo") MultipartFile logo) throws IOException {
        AdminUser studio = studio(auth, studioId);
        branding.uploadLogo(studio, logo.getBytes());
        return branding.of(studio);
    }

    @DeleteMapping("/{studioId}/logo")
    @Transactional
    public StudioBranding.Brand removeLogo(Authentication auth, @PathVariable UUID studioId) {
        AdminUser studio = studio(auth, studioId);
        branding.removeLogo(studio);
        return branding.of(studio);
    }

    private AdminUser studio(Authentication auth, UUID studioId) {
        access.requireSuperAdmin(auth);
        return users.findById(studioId).orElseThrow(() -> ApiException.notFound("Studio"));
    }
}
