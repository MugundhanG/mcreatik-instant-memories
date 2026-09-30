package com.mcreatik.uploader.api;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.function.LongConsumer;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Talks to the McreatiK backend (JSON) and to object storage (presigned PUT). */
public class ApiClient {

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    /** Headers the JDK client sets itself and refuses to accept. */
    private static final Set<String> RESTRICTED = Set.of("host", "content-length", "connection", "expect", "upgrade");
    private static final Set<String> BLOCKING_CODES = Set.of("EVENT_CLOSED");
    private static final Set<String> RETRYABLE_CONFLICTS = Set.of("UPLOAD_NOT_FOUND", "UPLOAD_INCOMPLETE");

    public static final String FILE_GONE = "File was moved or deleted before it could be uploaded";

    private final String serverUrl;
    private final String token;
    private final HttpClient http;

    public ApiClient(String serverUrl, String token) {
        this(serverUrl, token, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    public ApiClient(String serverUrl, String token, HttpClient http) {
        this.serverUrl = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        this.token = token;
        this.http = http;
    }

    public ApiModels.Me me() throws ApiException {
        return send(get("/api/uploader/me"), ApiModels.Me.class);
    }

    public ApiModels.HeartbeatResponse heartbeat(ApiModels.HeartbeatRequest request) throws ApiException {
        return send(post("/api/uploader/heartbeat", request), ApiModels.HeartbeatResponse.class);
    }

    public ApiModels.UploadSession createUpload(ApiModels.CreateUploadRequest request) throws ApiException {
        return send(post("/api/uploader/uploads", request), ApiModels.UploadSession.class);
    }

    public ApiModels.CompleteResponse complete(String photoId) throws ApiException {
        return send(post("/api/uploader/uploads/" + photoId + "/complete", null), ApiModels.CompleteResponse.class);
    }

    /** Streams the file from disk straight to object storage. Never buffers the whole photo in memory. */
    public void putFile(ApiModels.PresignedUpload upload, Path file, LongConsumer bytesSent) throws ApiException {
        long size;
        HttpRequest.BodyPublisher body;
        try {
            size = Files.size(file);
            body = new CountingPublisher(HttpRequest.BodyPublishers.ofFile(file), bytesSent);
        } catch (java.nio.file.NoSuchFileException | java.io.FileNotFoundException e) {
            throw new ApiException(ApiException.Kind.PERMANENT, FILE_GONE, e);
        } catch (IOException e) {
            throw new ApiException(ApiException.Kind.PERMANENT, "Cannot read file: " + e.getMessage(), e);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(upload.url()))
                // generous: 60 MB over a slow 1 Mbit/s uplink still fits
                .timeout(Duration.ofSeconds(Math.max(120, size / 50_000)))
                .method(upload.method() == null ? "PUT" : upload.method(), body);
        if (upload.headers() != null) {
            upload.headers().forEach((k, v) -> {
                if (!RESTRICTED.contains(k.toLowerCase())) {
                    builder.header(k, v);
                }
            });
        }
        HttpResponse<String> response = execute(builder.build());
        int status = response.statusCode();
        if (status / 100 == 2) {
            return;
        }
        // 403 on a presigned URL = expired/invalid signature: ask for a new session and retry.
        if (status == 403 || status == 408 || status == 429 || status >= 500) {
            throw new ApiException(ApiException.Kind.TRANSIENT, status, "STORAGE_" + status,
                    "Storage temporarily rejected the upload (HTTP " + status + ")");
        }
        throw new ApiException(ApiException.Kind.PERMANENT, status, "STORAGE_" + status,
                "Storage rejected the file (HTTP " + status + ")");
    }

    // ---- plumbing ----

    private HttpRequest get(String path) {
        return base(path).GET().build();
    }

    private HttpRequest post(String path, Object body) {
        String json = body == null ? "" : JSON.writeValueAsString(body);
        return base(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
    }

    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(URI.create(serverUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
    }

    private <T> T send(HttpRequest request, Class<T> type) throws ApiException {
        HttpResponse<String> response = execute(request);
        int status = response.statusCode();
        if (status / 100 == 2) {
            try {
                return JSON.readValue(response.body(), type);
            } catch (JacksonException e) {
                throw new ApiException(ApiException.Kind.TRANSIENT, "Unexpected server response", e);
            }
        }
        ApiModels.ErrorBody error = parseError(response.body());
        String code = error == null ? null : error.code();
        String message = error != null && error.message() != null ? error.message() : "HTTP " + status;
        if (status == 401) {
            throw new ApiException(ApiException.Kind.BLOCKED, status, "UNAUTHORIZED",
                    "This uploader was disconnected in the McreatiK dashboard. Paste a new connection code.");
        }
        if (code != null && BLOCKING_CODES.contains(code)) {
            throw new ApiException(ApiException.Kind.BLOCKED, status, code, message);
        }
        if (status == 408 || status == 429 || status >= 500 || (code != null && RETRYABLE_CONFLICTS.contains(code))) {
            throw new ApiException(ApiException.Kind.TRANSIENT, status, code, message);
        }
        throw new ApiException(ApiException.Kind.PERMANENT, status, code, message);
    }

    private HttpResponse<String> execute(HttpRequest request) throws ApiException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ApiException(ApiException.Kind.TRANSIENT, "No connection to McreatiK (" + describe(e) + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ApiException.Kind.TRANSIENT, "Interrupted", e);
        }
    }

    private static String describe(IOException e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private static ApiModels.ErrorBody parseError(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(body, ApiModels.ErrorBody.class);
        } catch (JacksonException e) {
            return null;
        }
    }

    /** Wraps a body publisher and reports how many bytes have been handed to the network. */
    private static final class CountingPublisher implements HttpRequest.BodyPublisher {
        private final HttpRequest.BodyPublisher delegate;
        private final LongConsumer listener;

        CountingPublisher(HttpRequest.BodyPublisher delegate, LongConsumer listener) {
            this.delegate = delegate;
            this.listener = listener;
        }

        @Override
        public long contentLength() {
            return delegate.contentLength();
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            delegate.subscribe(new Flow.Subscriber<>() {
                private long sent;

                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    sent = 0;
                    subscriber.onSubscribe(subscription);
                }

                @Override
                public void onNext(ByteBuffer item) {
                    sent += item.remaining();
                    subscriber.onNext(item);
                    listener.accept(sent);
                }

                @Override
                public void onError(Throwable throwable) {
                    subscriber.onError(throwable);
                }

                @Override
                public void onComplete() {
                    subscriber.onComplete();
                }
            });
        }
    }
}
