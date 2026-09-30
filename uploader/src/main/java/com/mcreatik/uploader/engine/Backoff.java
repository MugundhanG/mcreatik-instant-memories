package com.mcreatik.uploader.engine;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Exponential backoff with jitter: 2s, 4s, 8s … capped (default 5 min). Jitter stops cameras retrying in lockstep. */
public record Backoff(Duration base, Duration max) {

    public static final Backoff DEFAULT = new Backoff(Duration.ofSeconds(2), Duration.ofMinutes(5));

    public long delayMillis(int attempt) {
        int exponent = Math.max(0, Math.min(attempt - 1, 20));
        long delay = Math.min(max.toMillis(), base.toMillis() * (1L << exponent));
        double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4; // ±20%
        return Math.max(1, (long) (delay * jitter));
    }
}
