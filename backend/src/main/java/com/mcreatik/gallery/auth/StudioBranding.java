package com.mcreatik.gallery.auth;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.mcreatik.gallery.common.ApiException;
import com.mcreatik.gallery.storage.StorageArea;
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
    public record Brand(String name, String logoUrl, String instagram, boolean mcreatik, int logoScale) {
    }

    /** The dashboard sends a resized PNG (<= 800 px); generous room for a JPEG/WebP logo too. */
    static final int MAX_LOGO_BYTES = 1024 * 1024;
    static final int MIN_LOGO_SCALE = 50;
    static final int MAX_LOGO_SCALE = 200;

    private final AdminUserRepository admins;
    private final StorageService storage;

    public StudioBranding(AdminUserRepository admins, StorageService storage) {
        this.admins = admins;
        this.storage = storage;
    }

    public Brand of(AdminUser owner) {
        return new Brand(owner.getDisplayName(),
                owner.getLogoKey() == null ? null : storage.publicMediaUrl(owner.getLogoKey()),
                owner.getInstagramHandle(), owner.isSuperAdmin(), owner.getLogoScale());
    }

    public Brand ofOwner(UUID ownerId) {
        return admins.findById(ownerId).map(this::of)
                .orElseGet(() -> new Brand("McreatiK Studios", null, null, true, 100));
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

    // ---- editing (the studio itself, or McreatiK for a studio) ----

    /** Fields left null stay as they are; a blank Instagram removes it. */
    public void edit(AdminUser user, String displayName, String instagram, Integer logoScale) {
        if (displayName != null) {
            if (displayName.isBlank() || displayName.trim().length() > 120) {
                throw ApiException.badRequest("INVALID_NAME", "Enter the studio name");
            }
            user.rename(displayName.trim());
        }
        if (instagram != null) {
            user.setInstagramHandle(normalizeInstagram(instagram));
        }
        if (logoScale != null) {
            if (logoScale < MIN_LOGO_SCALE || logoScale > MAX_LOGO_SCALE) {
                throw ApiException.badRequest("INVALID_SIZE", "The logo size must be between " + MIN_LOGO_SCALE + "% and "
                        + MAX_LOGO_SCALE + "%");
            }
            user.setLogoScale(logoScale);
        }
    }

    public void uploadLogo(AdminUser user, byte[] data) {
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
    }

    public void removeLogo(AdminUser user) {
        if (user.getLogoKey() != null) {
            storage.delete(StorageArea.MEDIA, user.getLogoKey());
            user.setLogoKey(null);
        }
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
}
