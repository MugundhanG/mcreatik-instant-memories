package com.mcreatik.gallery.auth;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import com.mcreatik.gallery.common.ApiException;

/** Who is calling: a studio (its own events) or the McreatiK super admin (everything). */
@Component
public class AdminAccess {

    private final AdminUserRepository users;

    public AdminAccess(AdminUserRepository users) {
        this.users = users;
    }

    public AdminUser current(Authentication auth) {
        return users.findById(CurrentAdmin.id(auth))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Please log in again"));
    }

    public boolean isSuperAdmin(UUID adminId) {
        return users.findById(adminId).map(AdminUser::isSuperAdmin).orElse(false);
    }

    public AdminUser requireSuperAdmin(Authentication auth) {
        AdminUser user = current(auth);
        if (!user.isSuperAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only McreatiK can do this");
        }
        return user;
    }
}
