package com.mcreatik.gallery.photo;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import com.mcreatik.gallery.common.ApiException;

/** Opaque keyset-pagination cursor: base64url("epochMicros:uuid"). */
public record Cursor(Instant at, UUID id) {

    public String encode() {
        long micros = at.getEpochSecond() * 1_000_000L + at.getNano() / 1_000;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((micros + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int sep = raw.indexOf(':');
            long micros = Long.parseLong(raw.substring(0, sep));
            Instant at = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L);
            return new Cursor(at, UUID.fromString(raw.substring(sep + 1)));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("INVALID_CURSOR", "Invalid cursor");
        }
    }
}
