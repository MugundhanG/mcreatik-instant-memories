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
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }
}
