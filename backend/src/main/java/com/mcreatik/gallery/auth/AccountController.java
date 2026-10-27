package com.mcreatik.gallery.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mcreatik.gallery.common.ApiException;

/** The logged-in studio's (or super admin's) own account. */
@RestController
@RequestMapping("/api/admin/account")
public class AccountController {

    static final int MIN_PASSWORD = 10;

    private final AdminAccess access;
    private final PasswordEncoder passwordEncoder;

    public AccountController(AdminAccess access, PasswordEncoder passwordEncoder) {
        this.access = access;
        this.passwordEncoder = passwordEncoder;
    }

    public record Account(String email, String displayName, AdminRole role, boolean mustChangePassword) {
        static Account of(AdminUser u) {
            return new Account(u.getEmail(), u.getDisplayName(), u.getRole(), u.isMustChangePassword());
        }
    }

    @GetMapping
    public Account me(Authentication auth) {
        return Account.of(access.current(auth));
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
