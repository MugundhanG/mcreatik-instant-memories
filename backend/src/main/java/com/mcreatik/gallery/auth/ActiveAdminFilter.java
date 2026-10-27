package com.mcreatik.gallery.auth;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A disabled (or deleted) studio is locked out at once, not only when its 12-hour token expires: every dashboard
 * request checks the account is still active.
 */
public class ActiveAdminFilter extends OncePerRequestFilter {

    private final AdminUserRepository users;

    public ActiveAdminFilter(AdminUserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt) {
            boolean active = users.findById(UUID.fromString(jwt.getToken().getSubject()))
                    .map(u -> !u.isDisabled()).orElse(false);
            if (!active) {
                SecurityContextHolder.clearContext();
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
