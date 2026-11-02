package com.mcreatik.gallery.auth;

import java.time.Instant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import com.mcreatik.gallery.common.ApiException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AdminUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    // Used to keep login timing similar whether or not the email exists.
    private final String dummyHash;

    private final PasswordResets resets;

    public AuthController(AdminUserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
                          PasswordResets resets) {
        this.resets = resets;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyHash = passwordEncoder.encode("timing-equaliser");
    }

    @PostMapping("/login")
    @Transactional
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        AdminUser user = users.findByEmail(request.email().trim()).orElse(null);
        String hash = user != null ? user.getPasswordHash() : dummyHash;
        boolean matches = passwordEncoder.matches(request.password(), hash);
        // "Forgot password": the emailed temporary password works too, until it expires.
        String resetHash = user == null || matches ? null : user.validResetHash(Instant.now());
        boolean temporary = resetHash != null && passwordEncoder.matches(request.password(), resetHash);
        if (user == null || !(matches || temporary)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
        }
        if (user.isDisabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED",
                    "This account is disabled. Please contact McreatiK.");
        }
        if (temporary) {
            // The temporary password becomes the password (other devices are logged out) and must be replaced now.
            user.setPassword(resetHash, true);
        }
        if (user.hasPendingReset()) {
            user.clearReset(); // used, or they remembered their password (saved when the transaction ends)
        }
        JwtService.IssuedToken token = jwtService.issue(user);
        return new LoginResponse(token.token(), token.expiresAt(), user.getEmail(), user.getDisplayName(),
                user.getRole(), user.isMustChangePassword());
    }

    public record ForgotPasswordRequest(@NotBlank @Size(max = 255) String email) {
    }

    /** Always 204, whether or not the email has an account. */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        resets.requestForStudio(request.email());
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank @Size(max = 200) String password) {
    }

    public record LoginResponse(String token, Instant expiresAt, String email, String displayName, AdminRole role,
                                boolean mustChangePassword) {
    }
}
