package com.mcreatik.uploader.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single string an admin copies from the dashboard: {@code MCK1.<base64url({"server":..,"token":..})>}.
 * Saves photographers from typing a server URL and a 48-character token at a venue.
 */
public record ConnectionCode(String server, String token) {

    private static final String PREFIX = "MCK1.";
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public static ConnectionCode parse(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Connection code is empty");
        }
        String trimmed = code.trim();
        if (!trimmed.startsWith(PREFIX)) {
            throw new IllegalArgumentException("This is not a McreatiK connection code (it should start with MCK1.)");
        }
        ConnectionCode parsed;
        try {
            String json = new String(Base64.getUrlDecoder().decode(trimmed.substring(PREFIX.length())), StandardCharsets.UTF_8);
            parsed = JSON.readValue(json, ConnectionCode.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Connection code is damaged. Copy it again from the dashboard.", e);
        }
        if (parsed == null || parsed.server() == null || !parsed.server().startsWith("http") || parsed.token() == null
                || !parsed.token().startsWith("mku_")) {
            throw new IllegalArgumentException("Connection code is incomplete. Copy it again from the dashboard.");
        }
        return parsed;
    }

    public static String encode(String server, String token) {
        String json = "{\"server\":\"" + server + "\",\"token\":\"" + token + "\"}";
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
