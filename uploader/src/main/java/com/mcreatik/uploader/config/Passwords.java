package com.mcreatik.uploader.config;

import java.security.SecureRandom;

public final class Passwords {

    // No 0/o, 1/l/i: easy to read off a laptop screen and type on a camera.
    private static final char[] ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {
    }

    public static String cameraFriendly(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
