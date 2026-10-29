package com.mcreatik.gallery.auth;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.mcreatik.gallery.common.ApiException;
import com.mcreatik.gallery.storage.StorageService;

/** The brand a guest sees on an event's gallery and poster: the studio that owns the event. */
@Component
public class StudioBranding {

    private static final Pattern INSTAGRAM_URL =
            Pattern.compile("^(?:https?://)?(?:www\\.)?instagram\\.com/([^/?#]+).*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSTAGRAM_HANDLE = Pattern.compile("^[A-Za-z0-9._]{1,30}$");

    /**
     * {@code mcreatik} is true for McreatiK's own events: the web app then shows McreatiK's built-in design.
     * For studios, {@code logoUrl} and {@code instagram} are null until the studio sets them.
     */
    public record Brand(String name, String logoUrl, String instagram, boolean mcreatik) {
    }

    private final AdminUserRepository admins;
    private final StorageService storage;

    public StudioBranding(AdminUserRepository admins, StorageService storage) {
        this.admins = admins;
        this.storage = storage;
    }

    public Brand of(AdminUser owner) {
        return new Brand(owner.getDisplayName(),
                owner.getLogoKey() == null ? null : storage.publicMediaUrl(owner.getLogoKey()),
                owner.getInstagramHandle(), owner.isSuperAdmin());
    }

    public Brand ofOwner(UUID ownerId) {
        return admins.findById(ownerId).map(this::of)
                .orElseGet(() -> new Brand("McreatiK Studios", null, null, true));
    }

    /** Accepts "name", "@name" or a profile link; blank clears it. */
    public static String normalizeInstagram(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String handle = input.trim();
        Matcher url = INSTAGRAM_URL.matcher(handle);
        if (url.matches()) {
            handle = url.group(1);
        }
        handle = handle.replaceFirst("^@", "");
        if (!INSTAGRAM_HANDLE.matcher(handle).matches()) {
            throw ApiException.badRequest("INVALID_INSTAGRAM",
                    "Enter an Instagram username like @yourstudio (letters, numbers, dots and underscores)");
        }
        return handle.toLowerCase(Locale.ROOT);
    }
}
