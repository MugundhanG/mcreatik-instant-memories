package com.mcreatik.gallery.auth;

import java.io.IOException;

import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.mcreatik.gallery.common.ApiException;

/** The logged-in studio's (or super admin's) own account. */
@RestController
@RequestMapping("/api/admin/account")
public class AccountController {

    static final int MIN_PASSWORD = 10;

    private final AdminAccess access;
    private final PasswordEncoder passwordEncoder;
    private final StudioBranding branding;

    public AccountController(AdminAccess access, PasswordEncoder passwordEncoder, StudioBranding branding) {
        this.access = access;
        this.passwordEncoder = passwordEncoder;
        this.branding = branding;
    }

    /** {@code brand} is what guests see on this studio's galleries and posters. */
    public record Account(String email, String displayName, AdminRole role, boolean mustChangePassword,
                          StudioBranding.Brand brand) {
    }

    private Account account(AdminUser u) {
        return new Account(u.getEmail(), u.getDisplayName(), u.getRole(), u.isMustChangePassword(), branding.of(u));
    }

    @GetMapping
    public Account me(Authentication auth) {
        return account(access.current(auth));
    }

    /** Studio name and Instagram (blank Instagram removes it). Fields left out stay as they are. */
    public record UpdateAccountRequest(@Size(min = 1, max = 120) String displayName, @Size(max = 200) String instagram,
                                       Integer logoScale) {
    }

    @PatchMapping
    @Transactional
    public Account update(Authentication auth, @Valid @RequestBody UpdateAccountRequest request) {
        AdminUser user = access.current(auth);
        branding.edit(user, request.displayName(), request.instagram(), request.logoScale());
        return account(user);
    }

    @PostMapping(value = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public Account uploadLogo(Authentication auth, @RequestPart("logo") MultipartFile logo) throws IOException {
        AdminUser user = access.current(auth);
        branding.uploadLogo(user, logo.getBytes());
        return account(user);
    }

    @DeleteMapping("/logo")
    @Transactional
    public Account removeLogo(Authentication auth) {
        AdminUser user = access.current(auth);
        branding.removeLogo(user);
        return account(user);
    }

    public record ChangePasswordRequest(@NotBlank @Size(max = 200) String currentPassword,
                                        @NotBlank @Size(min = MIN_PASSWORD, max = 200) String newPassword) {
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void changePassword(Authentication auth, @Valid @RequestBody ChangePasswordRequest request) {
        AdminUser user = access.current(auth);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("WRONG_PASSWORD", "The current password is not correct");
        }
        if (request.currentPassword().equals(request.newPassword())) {
            throw ApiException.badRequest("SAME_PASSWORD", "Choose a new password, different from the current one");
        }
        user.setPassword(passwordEncoder.encode(request.newPassword()), false);
    }
}
