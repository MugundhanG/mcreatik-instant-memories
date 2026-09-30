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
import org.springframework.web.bind.annotation.RestController;

import com.mcreatik.gallery.common.ApiException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AdminUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    // Used to keep login timing similar whether or not the email exists.
    private final String dummyHash;

    public AuthController(AdminUserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyHash = passwordEncoder.encode("timing-equaliser");
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        AdminUser user = users.findByEmail(request.email().trim()).orElse(null);
        String hash = user != null ? user.getPasswordHash() : dummyHash;
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (user == null || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
        }
        JwtService.IssuedToken token = jwtService.issue(user);
        return new LoginResponse(token.token(), token.expiresAt(), user.getEmail(), user.getDisplayName());
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank @Size(max = 200) String password) {
    }

    public record LoginResponse(String token, Instant expiresAt, String email, String displayName) {
    }
}
