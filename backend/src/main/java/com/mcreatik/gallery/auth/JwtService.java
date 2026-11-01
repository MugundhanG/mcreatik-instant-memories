package com.mcreatik.gallery.auth;

import java.time.Instant;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.mcreatik.gallery.config.GalleryProperties;

@Service
public class JwtService {

    public static final String ISSUER = "mcreatik-live-gallery";

    private final JwtEncoder encoder;
    private final GalleryProperties properties;

    public JwtService(JwtEncoder encoder, GalleryProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    /** Claim with the account's token version: a password change makes older tokens invalid. */
    public static final String VERSION_CLAIM = "ver";

    /** The version a token was issued for; tokens from before versions existed count as 0. */
    public static int version(org.springframework.security.oauth2.jwt.Jwt jwt) {
        Object v = jwt.getClaims().get(VERSION_CLAIM);
        return v instanceof Number n ? n.intValue() : 0;
    }

    public IssuedToken issue(AdminUser user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.security().jwtTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("email", user.getEmail())
                .claim("scope", "admin")
                .claim(VERSION_CLAIM, user.getTokenVersion())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    /** The couple's login: scope "client", limited to their one event. */
    public IssuedToken issueClient(java.util.UUID clientId, java.util.UUID eventId, String email, int version) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.security().jwtTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(clientId.toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("email", email)
                .claim("event", eventId.toString())
                .claim("scope", "client")
                .claim(VERSION_CLAIM, version)
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }
}
