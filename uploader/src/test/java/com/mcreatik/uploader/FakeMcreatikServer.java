package com.mcreatik.uploader;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * In-process stand-in for the McreatiK backend + object storage, implementing the uploader protocol.
 * Can simulate a dead network (connections dropped), server errors and a revoked token.
 */
public class FakeMcreatikServer implements AutoCloseable {

    public static final String TOKEN = "mku_test-token";
    private static final Pattern SHA = Pattern.compile("\"checksumSha256\"\\s*:\\s*\"([a-f0-9]{64})\"");

    private final HttpServer server;
    /** sha256 → photoId */
    public final Map<String, String> photosBySha = new ConcurrentHashMap<>();
    /** photoId → stored bytes */
    public final Map<String, byte[]> stored = new ConcurrentHashMap<>();
    public final Set<String> completed = ConcurrentHashMap.newKeySet();

    public final AtomicBoolean offline = new AtomicBoolean();
    public final AtomicBoolean revoked = new AtomicBoolean();
    public final AtomicInteger failNextPuts = new AtomicInteger();
    public volatile String rejectUploadsWithCode;

    public final AtomicInteger sessionCalls = new AtomicInteger();
    public final AtomicInteger putCalls = new AtomicInteger();
    public final AtomicInteger heartbeats = new AtomicInteger();

    public FakeMcreatikServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Photos that were fully uploaded and completed, with their content hashes. */
    public Set<String> completedShas() {
        Set<String> shas = ConcurrentHashMap.newKeySet();
        photosBySha.forEach((sha, id) -> {
            if (completed.contains(id)) {
                shas.add(sha);
            }
        });
        return shas;
    }

    private void handle(HttpExchange ex) throws IOException {
        if (offline.get()) {
            ex.close(); // drop the connection like a dead router would
            return;
        }
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        byte[] body = ex.getRequestBody().readAllBytes();

        if (path.startsWith("/storage/") && method.equals("PUT")) {
            putCalls.incrementAndGet();
            if (failNextPuts.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                respond(ex, 503, "");
                return;
            }
            stored.put(path.substring("/storage/".length()), body);
            respond(ex, 200, "");
            return;
        }
        if (revoked.get() || !("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            respond(ex, 401, "{\"code\":\"UNAUTHORIZED\",\"message\":\"Invalid uploader token\"}");
            return;
        }
        switch (method + " " + path) {
            case "GET /api/uploader/me" -> respond(ex, 200, """
                    {"uploaderId":"u1","uploaderName":"Camera 1","eventId":"e1","eventName":"Arun & Priya Wedding",
                     "eventStatus":"LIVE","galleryUrl":"https://gallery.mcreatik.com/e/arun-priya-7k3d",
                     "heartbeatIntervalSeconds":15,"maxFileSizeBytes":62914560}""");
            case "POST /api/uploader/heartbeat" -> {
                heartbeats.incrementAndGet();
                respond(ex, 200, "{\"eventStatus\":\"LIVE\",\"acceptingUploads\":true}");
            }
            case "POST /api/uploader/uploads" -> createSession(ex, new String(body, StandardCharsets.UTF_8));
            default -> {
                if (method.equals("POST") && path.startsWith("/api/uploader/uploads/") && path.endsWith("/complete")) {
                    String id = path.substring("/api/uploader/uploads/".length(), path.length() - "/complete".length());
                    byte[] bytes = stored.get(id);
                    if (bytes == null) {
                        respond(ex, 409, "{\"code\":\"UPLOAD_NOT_FOUND\",\"message\":\"not stored\"}");
                        return;
                    }
                    String sha = photosBySha.entrySet().stream().filter(e -> e.getValue().equals(id))
                            .map(Map.Entry::getKey).findFirst().orElseThrow();
                    if (!sha.equals(sha256(bytes))) {
                        respond(ex, 409, "{\"code\":\"UPLOAD_INCOMPLETE\",\"message\":\"bad bytes\"}");
                        return;
                    }
                    completed.add(id);
                    respond(ex, 200, "{\"photoId\":\"" + id + "\",\"status\":\"UPLOADED\"}");
                } else {
                    respond(ex, 404, "{\"code\":\"NOT_FOUND\",\"message\":\"nope\"}");
                }
            }
        }
    }

    private void createSession(HttpExchange ex, String json) throws IOException {
        sessionCalls.incrementAndGet();
        if (rejectUploadsWithCode != null) {
            respond(ex, 400, "{\"code\":\"" + rejectUploadsWithCode + "\",\"message\":\"Rejected by test\"}");
            return;
        }
        Matcher m = SHA.matcher(json);
        if (!m.find()) {
            respond(ex, 400, "{\"code\":\"INVALID_CHECKSUM\",\"message\":\"missing\"}");
            return;
        }
        String sha = m.group(1);
        String id = photosBySha.computeIfAbsent(sha, k -> UUID.randomUUID().toString());
        if (completed.contains(id)) {
            respond(ex, 200, "{\"outcome\":\"DUPLICATE\",\"photoId\":\"" + id + "\",\"upload\":null}");
            return;
        }
        respond(ex, 200, "{\"outcome\":\"UPLOAD\",\"photoId\":\"" + id + "\",\"upload\":{\"url\":\"" + url()
                + "/storage/" + id + "\",\"method\":\"PUT\",\"headers\":{\"Content-Type\":\"image/jpeg\"},"
                + "\"expiresAt\":\"2030-01-01T00:00:00Z\"}}");
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
        ex.close();
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
