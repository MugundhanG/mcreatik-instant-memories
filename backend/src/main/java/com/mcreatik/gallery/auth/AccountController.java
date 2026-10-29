package com.mcreatik.gallery.auth;

import java.io.IOException;
import java.util.UUID;

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
import com.mcreatik.gallery.storage.StorageArea;
import com.mcreatik.gallery.storage.StorageService;

/** The logged-in studio's (or super admin's) own account. */
@RestController
@RequestMapping("/api/admin/account")
public class AccountController {

    static final int MIN_PASSWORD = 10;
    /** The dashboard sends a resized PNG (<= 800 px); generous room for a JPEG/WebP logo too. */
    static final int MAX_LOGO_BYTES = 1024 * 1024;

    private final AdminAccess access;
    private final PasswordEncoder passwordEncoder;
    private final StudioBranding branding;
    private final StorageService storage;

    public AccountController(AdminAccess access, PasswordEncoder passwordEncoder, StudioBranding branding,
                             StorageService storage) {
        this.access = access;
        this.passwordEncoder = passwordEncoder;
        this.branding = branding;
        this.storage = storage;
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
    public record UpdateAccountRequest(@Size(min = 1, max = 120) String displayName, @Size(max = 200) String instagram) {
    }

    @PatchMapping
    @Transactional
    public Account update(Authentication auth, @Valid @RequestBody UpdateAccountRequest request) {
        AdminUser user = access.current(auth);
        if (request.displayName() != null) {
            if (request.displayName().isBlank()) {
                throw ApiException.badRequest("INVALID_NAME", "Enter the studio name");
            }
            user.rename(request.displayName().trim());
        }
        if (request.instagram() != null) {
            user.setInstagramHandle(StudioBranding.normalizeInstagram(request.instagram()));
        }
        return account(user);
    }

    @PostMapping(value = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public Account uploadLogo(Authentication auth, @RequestPart("logo") MultipartFile logo) throws IOException {
        AdminUser user = access.current(auth);
        byte[] data = logo.getBytes();
        String type = imageType(data);
        if (type == null) {
            throw ApiException.badRequest("UNSUPPORTED_TYPE", "The logo must be a PNG, JPEG or WebP image");
        }
        if (data.length > MAX_LOGO_BYTES) {
            throw ApiException.badRequest("FILE_TOO_LARGE", "The logo must be under 1 MB");
        }
        // A new key each time: browsers and the CDN never show a cached old logo.
        String key = "branding/" + user.getId() + "/logo-" + UUID.randomUUID() + "." + type.substring(6);
        storage.write(StorageArea.MEDIA, key, data, type);
        String previous = user.getLogoKey();
        user.setLogoKey(key);
        if (previous != null) {
            storage.delete(StorageArea.MEDIA, previous);
        }
        return account(user);
    }

    @DeleteMapping("/logo")
    @Transactional
    public Account removeLogo(Authentication auth) {
        AdminUser user = access.current(auth);
        if (user.getLogoKey() != null) {
            storage.delete(StorageArea.MEDIA, user.getLogoKey());
            user.setLogoKey(null);
        }
        return account(user);
    }

    /** Content type from the file's first bytes (never trust the declared type). */
    static String imageType(byte[] d) {
        if (d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "image/png";
        }
        if (d.length > 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (d.length > 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return "image/webp";
        }
        return null;
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
