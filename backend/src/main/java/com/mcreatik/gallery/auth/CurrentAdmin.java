package com.mcreatik.gallery.auth;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public final class CurrentAdmin {

    private CurrentAdmin() {
    }

    public static UUID id(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return UUID.fromString(jwt.getToken().getSubject());
        }
        throw new IllegalStateException("Not an admin authentication");
    }
}
