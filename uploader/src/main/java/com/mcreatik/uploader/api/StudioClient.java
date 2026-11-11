package com.mcreatik.uploader.api;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import tools.jackson.core.JacksonException;

/**
 * The studio's own login (the same email and password as the website): sign in, list events, create an uploader
 * for one. The returned uploader token is exactly what a pasted connection code carries.
 */
public class StudioClient {

    public static final String DEFAULT_SERVER = "https://im-api.mcreatik.com";
    public static final String NO_INTERNET = "No internet: check the laptop's Wi-Fi";
    public static final String MUST_CHANGE_PASSWORD = "MUST_CHANGE_PASSWORD";
    public static final String SESSION_EXPIRED = "SESSION_EXPIRED";

    private final String serverUrl;
    private final HttpClient http;

    public StudioClient(String serverUrl) {
        this(serverUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    public StudioClient(String serverUrl, HttpClient http) {
        this.serverUrl = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        this.http = http;
    }

    /** The server to talk to: {@code -Dmcreatik.server} (tests), else the saved one, else the default. */
    public static String resolveServer(String saved) {
        String override = System.getProperty("mcreatik.server");
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        return saved == null || saved.isBlank() ? DEFAULT_SERVER : saved;
    }

    public ApiModels.LoginResponse login(String email, String password) throws ApiException {
        ApiModels.LoginResponse r = send(request("/api/auth/login", null)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ApiClient.JSON.writeValueAsString(
                        new ApiModels.LoginRequest(email, password)))).build(), ApiModels.LoginResponse.class, true);
        if (r.mustChangePassword()) {
            throw new ApiException(ApiException.Kind.PERMANENT, 200, MUST_CHANGE_PASSWORD,
                    "Set your new password on the website first");
        }
        return r;
    }

    public List<ApiModels.StudioEvent> events(String jwt) throws ApiException {
        return List.of(send(request("/api/admin/events", jwt).GET().build(), ApiModels.StudioEvent[].class, false));
    }

    public ApiModels.UploaderCredentials createUploader(String jwt, String eventId, String name) throws ApiException {
        return send(request("/api/admin/events/" + eventId + "/uploaders", jwt)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ApiClient.JSON.writeValueAsString(
                        new ApiModels.CreateUploaderRequest(name)))).build(), ApiModels.UploaderCredentials.class, false);
    }

    private HttpRequest.Builder request(String path, String jwt) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(serverUrl + path))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json");
        return jwt == null ? b : b.header("Authorization", "Bearer " + jwt);
    }

    private <T> T send(HttpRequest request, Class<T> type, boolean login) throws ApiException {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ApiException(ApiException.Kind.TRANSIENT, NO_INTERNET, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ApiException.Kind.TRANSIENT, "Interrupted", e);
        }
        int status = response.statusCode();
        if (status / 100 == 2) {
            try {
                return ApiClient.JSON.readValue(response.body(), type);
            } catch (JacksonException e) {
                throw new ApiException(ApiException.Kind.TRANSIENT, "Unexpected server response", e);
            }
        }
        ApiModels.ErrorBody error = null;
        try {
            error = response.body() == null || response.body().isBlank() ? null
                    : ApiClient.JSON.readValue(response.body(), ApiModels.ErrorBody.class);
        } catch (JacksonException ignored) {
            // not JSON: use the status below
        }
        String code = error == null ? null : error.code();
        String message = error != null && error.message() != null ? error.message() : "HTTP " + status;
        if (status == 401) {
            throw login
                    ? new ApiException(ApiException.Kind.PERMANENT, status, "INVALID_CREDENTIALS",
                            "Email or password is wrong")
                    : new ApiException(ApiException.Kind.PERMANENT, status, SESSION_EXPIRED,
                            "Your sign-in has expired. Sign in again.");
        }
        if (status == 429) {
            throw new ApiException(ApiException.Kind.TRANSIENT, status, "TOO_MANY_TRIES",
                    "Too many tries, wait a minute");
        }
        if (status == 408 || status >= 500) {
            throw new ApiException(ApiException.Kind.TRANSIENT, status, code, "McreatiK had a problem. Try again in a moment.");
        }
        throw new ApiException(ApiException.Kind.PERMANENT, status, code, message);
    }
}
