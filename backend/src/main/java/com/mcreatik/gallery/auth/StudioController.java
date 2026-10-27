package com.mcreatik.gallery.auth;

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
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mcreatik.gallery.common.ApiException;
import com.mcreatik.gallery.event.EventRepository;

/** Super admin only: client studio logins, with their usage for billing. */
@RestController
@RequestMapping("/api/admin/studios")
public class StudioController {

    private final AdminUserRepository users;
    private final AdminAccess access;
    private final PasswordEncoder passwordEncoder;
    private final EventRepository events;

    public StudioController(AdminUserRepository users, AdminAccess access, PasswordEncoder passwordEncoder,
                            EventRepository events) {
        this.users = users;
        this.access = access;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
    }

    public record Studio(UUID id, String name, String email, AdminRole role, boolean disabled,
                         boolean mustChangePassword, Instant createdAt, long events, long photos, long storageBytes) {
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
                    u.isMustChangePassword(), u.getCreatedAt(), n[0], n[1], n[2]);
        }).toList();
    }

    public record CreateStudioRequest(@NotBlank @Size(max = 120) String name, @NotBlank @Email @Size(max = 320) String email,
                                      @NotBlank @Size(min = AccountController.MIN_PASSWORD, max = 200) String password) {
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
        users.save(studio);
        return new Studio(studio.getId(), studio.getDisplayName(), studio.getEmail(), studio.getRole(), false, true,
                studio.getCreatedAt(), 0, 0, 0);
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
                studio.isDisabled(), studio.isMustChangePassword(), studio.getCreatedAt(), 0, 0, 0);
    }
}
