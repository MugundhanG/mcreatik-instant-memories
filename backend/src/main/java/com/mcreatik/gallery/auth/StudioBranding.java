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
     * For studios, {@code logoUrl}, {@code instagram} and {@code phone} are null until the studio sets them.
     */
    public record Brand(String name, String logoUrl, String instagram, boolean mcreatik, int logoScale,
                        String boardTheme, String phone) {
    }

    /** The designs a studio can pick for its printed board and event cards. */
    static final java.util.Set<String> BOARD_THEMES = java.util.Set.of("cream", "dark", "white");

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
                owner.getInstagramHandle(), owner.isSuperAdmin(), owner.getLogoScale(),
                owner.getBoardTheme(), owner.getContactPhone());
    }

    /** A logo file as stored: its bytes and image type. */
    public record LogoFile(byte[] data, String contentType) {
    }

    /**
     * The owner's logo bytes, for the dashboard's print files. Served through the API (not the public media URL)
     * so the browser may draw it into a downloadable image without the media domain's CORS settings.
     */
    public java.util.Optional<LogoFile> logoFile(UUID ownerId) {
        return admins.findById(ownerId).map(AdminUser::getLogoKey).map(key -> {
            String ext = key.substring(key.lastIndexOf('.') + 1);
            return new LogoFile(storage.read(StorageArea.MEDIA, key), "image/" + ext);
        });
    }

    public Brand ofOwner(UUID ownerId) {
        return admins.findById(ownerId).map(this::of)
                .orElseGet(() -> new Brand("McreatiK Studios", null, null, true, 100, "cream", null));
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
        edit(user, displayName, instagram, logoScale, null, null);
    }

    public void edit(AdminUser user, String displayName, String instagram, Integer logoScale, String boardTheme) {
        edit(user, displayName, instagram, logoScale, boardTheme, null);
    }

    /** As above, plus the board design ({@code cream}, {@code dark} or {@code white}). */
    public void edit(AdminUser user, String displayName, String instagram, Integer logoScale, String boardTheme,
                     String phone) {
        if (boardTheme != null && !BOARD_THEMES.contains(boardTheme)) {
            throw ApiException.badRequest("INVALID_THEME", "Pick the cream, dark or white board design");
        }
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
        if (boardTheme != null) {
            user.setBoardTheme(boardTheme);
        }
        if (phone != null) {
            user.setContactPhone(normalizePhone(phone));
        }
    }

    /**
     * A contact number as guests will see and dial it: blank removes it; spaces, dashes and brackets are dropped;
     * a 10-digit Indian mobile number (or one starting 0) gets +91. 8 to 15 digits, optionally after a +.
     */
    static String normalizePhone(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        String digits = s.replaceAll("[\\s().-]", "");
        if (digits.startsWith("00")) {
            digits = "+" + digits.substring(2);
        }
        if (digits.matches("0\\d{10}")) {
            digits = digits.substring(1);
        }
        if (digits.matches("[6-9]\\d{9}")) {
            digits = "+91" + digits;
        } else if (digits.matches("91[6-9]\\d{9}")) {
            digits = "+" + digits;
        }
        if (!digits.matches("\\+?\\d{8,15}")) {
            throw ApiException.badRequest("INVALID_PHONE", "Enter a phone number, e.g. 98765 43210 or +91 98765 43210");
        }
        return digits;
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
