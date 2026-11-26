package com.mcreatik.gallery.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "admin_users")
public class AdminUser {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    /** "Forgot password": hash of the emailed temporary password, valid until resetExpiresAt. */
    @Column(name = "reset_password_hash")
    private String resetPasswordHash;

    @Column(name = "reset_expires_at")
    private Instant resetExpiresAt;

    @Column(name = "reset_requested_at")
    private Instant resetRequestedAt;

    /** Goes up on every password change or reset; older tokens stop working. */
    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdminRole role;

    @Column(nullable = false)
    private boolean disabled;

    /** Set when the super admin chose the password: the studio picks its own at the next login. */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "instagram_handle")
    private String instagramHandle;

    /** Contact number shown to guests (normalised, e.g. +919876543210), or null. */
    @Column(name = "contact_phone")
    private String contactPhone;

    @Column(name = "logo_key")
    private String logoKey;

    /** Poster logo size, percent of the standard size (50 to 200). */
    @Column(name = "logo_scale", nullable = false)
    private int logoScale = 100;

    /** Design of the printed studio board and event cards: cream, dark or white. */
    @Column(name = "board_theme", nullable = false)
    private String boardTheme = "cream";

    /** The watermark on guests' downloads: CORNER or STRIP, where it sits, how big, and which details it shows. */
    @Column(name = "wm_style", nullable = false)
    private String wmStyle = "CORNER";

    @Column(name = "wm_position", nullable = false)
    private String wmPosition = "RIGHT";

    @Column(name = "wm_size", nullable = false)
    private String wmSize = "SMALL";

    @Column(name = "wm_logo", nullable = false)
    private boolean wmLogo = true;

    @Column(name = "wm_name", nullable = false)
    private boolean wmName = false;

    @Column(name = "wm_instagram", nullable = false)
    private boolean wmInstagram = true;

    @Column(name = "wm_phone", nullable = false)
    private boolean wmPhone = false;

    protected AdminUser() {
    }

    /** A studio login. */
    public AdminUser(String email, String passwordHash, String displayName) {
        this(email, passwordHash, displayName, AdminRole.STUDIO);
    }

    public AdminUser(String email, String passwordHash, String displayName, AdminRole role) {
        this.id = UUID.randomUUID();
        this.email = email.trim().toLowerCase();
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
        this.createdAt = Instant.now();
    }

    public AdminRole getRole() {
        return role;
    }

    public boolean isSuperAdmin() {
        return role == AdminRole.SUPER_ADMIN;
    }

    public boolean isDisabled() {
        return disabled;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Instagram username shown on the studio's galleries and posters, without "@"; null hides it. */
    public String getInstagramHandle() {
        return instagramHandle;
    }

    public void setInstagramHandle(String instagramHandle) {
        this.instagramHandle = instagramHandle;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    /** Media-storage key of the studio's logo; null shows the studio name only. */
    public String getLogoKey() {
        return logoKey;
    }

    public void setLogoKey(String logoKey) {
        this.logoKey = logoKey;
    }

    public int getLogoScale() {
        return logoScale;
    }

    public void setLogoScale(int logoScale) {
        this.logoScale = logoScale;
    }

    public String getBoardTheme() {
        return boardTheme;
    }

    public void setBoardTheme(String boardTheme) {
        this.boardTheme = boardTheme;
    }

    public String getWmStyle() {
        return wmStyle;
    }

    public void setWmStyle(String wmStyle) {
        this.wmStyle = wmStyle;
    }

    public String getWmPosition() {
        return wmPosition;
    }

    public void setWmPosition(String wmPosition) {
        this.wmPosition = wmPosition;
    }

    public String getWmSize() {
        return wmSize;
    }

    public void setWmSize(String wmSize) {
        this.wmSize = wmSize;
    }

    public boolean isWmLogo() {
        return wmLogo;
    }

    public void setWmLogo(boolean wmLogo) {
        this.wmLogo = wmLogo;
    }

    public boolean isWmName() {
        return wmName;
    }

    public void setWmName(boolean wmName) {
        this.wmName = wmName;
    }

    public boolean isWmInstagram() {
        return wmInstagram;
    }

    public void setWmInstagram(boolean wmInstagram) {
        this.wmInstagram = wmInstagram;
    }

    public boolean isWmPhone() {
        return wmPhone;
    }

    public void setWmPhone(boolean wmPhone) {
        this.wmPhone = wmPhone;
    }

    public void rename(String displayName) {
        this.displayName = displayName;
    }

    /** {@code temporary}: chosen by the super admin, so the studio must replace it. */
    public void setPassword(String passwordHash, boolean temporary) {
        this.passwordHash = passwordHash;
        this.mustChangePassword = temporary;
        this.tokenVersion++;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    /** A temporary password was emailed; the current password keeps working until it is used. */
    public void startReset(String temporaryHash, Instant expiresAt, Instant now) {
        this.resetPasswordHash = temporaryHash;
        this.resetExpiresAt = expiresAt;
        this.resetRequestedAt = now;
    }

    public void clearReset() {
        this.resetPasswordHash = null;
        this.resetExpiresAt = null;
    }

    /** The emailed temporary password's hash while it is still valid, else null. */
    public String validResetHash(Instant now) {
        return resetPasswordHash != null && resetExpiresAt != null && resetExpiresAt.isAfter(now) ? resetPasswordHash : null;
    }

    public boolean hasPendingReset() {
        return resetPasswordHash != null;
    }

    public Instant getResetRequestedAt() {
        return resetRequestedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }
}
