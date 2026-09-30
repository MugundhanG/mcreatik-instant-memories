package com.mcreatik.gallery.uploader;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.mcreatik.gallery.common.Tokens;

/**
 * Authenticates uploader apps with "Authorization: Bearer mku_...". Only the SHA-256 hash of the
 * token is stored, so a database leak does not leak working credentials.
 */
public class UploaderTokenAuthenticationFilter extends OncePerRequestFilter {

    public static final String TOKEN_PREFIX = "mku_";

    private final UploaderRepository uploaders;

    public UploaderTokenAuthenticationFilter(UploaderRepository uploaders) {
        this.uploaders = uploaders;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer " + TOKEN_PREFIX)) {
            unauthorized(response);
            return;
        }
        String token = header.substring("Bearer ".length()).trim();
        var uploader = uploaders.findByTokenHash(Tokens.sha256Hex(token)).orElse(null);
        if (uploader == null) {
            unauthorized(response);
            return;
        }
        uploaders.touch(uploader.getId());
        var principal = new UploaderPrincipal(uploader.getId(), uploader.getEventId());
        var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_UPLOADER")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Invalid uploader token\"}");
    }
}
