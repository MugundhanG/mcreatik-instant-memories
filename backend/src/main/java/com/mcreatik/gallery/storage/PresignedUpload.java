package com.mcreatik.gallery.storage;

import java.time.Instant;
import java.util.Map;

/** Everything a client needs to PUT a file directly to object storage. */
public record PresignedUpload(String url, String method, Map<String, String> headers, Instant expiresAt) {
}
