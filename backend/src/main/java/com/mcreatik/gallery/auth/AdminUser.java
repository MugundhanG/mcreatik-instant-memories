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

    @Column(name = "logo_key")
    private String logoKey;

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

    /** Media-storage key of the studio's logo; null shows the studio name only. */
    public String getLogoKey() {
        return logoKey;
    }

    public void setLogoKey(String logoKey) {
        this.logoKey = logoKey;
    }

    public void rename(String displayName) {
        this.displayName = displayName;
    }

    /** {@code temporary}: chosen by the super admin, so the studio must replace it. */
    public void setPassword(String passwordHash, boolean temporary) {
        this.passwordHash = passwordHash;
        this.mustChangePassword = temporary;
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
