package com.mcreatik.uploader.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class StudioClientTest {

    private HttpServer server;
    private StudioClient client;
    private final List<String> requests = new ArrayList<>();
    private volatile int loginStatus = 200;
    private volatile String loginBody = """
            {"token":"jwt-1","expiresAt":"2026-12-01T12:00:00Z","email":"hello@lenslight.test",
             "displayName":"Lens & Light Studio","role":"STUDIO","mustChangePassword":false}""";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/login", ex -> {
            requests.add("POST login " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, loginStatus, loginBody);
        });
        server.createContext("/api/admin/events", ex -> {
            String path = ex.getRequestURI().getPath();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            requests.add(ex.getRequestMethod() + " " + path + " auth=" + auth + " "
                    + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (!"Bearer jwt-1".equals(auth)) {
                reply(ex, 401, "{\"code\":\"UNAUTHORIZED\",\"message\":\"no\"}");
            } else if (path.endsWith("/uploaders")) {
                reply(ex, 201, "{\"uploaderId\":\"u-1\",\"name\":\"LENS-PC\",\"token\":\"mku_abc\",\"connectionCode\":\"MCK1.x\"}");
            } else {
                reply(ex, 200, """
                        [{"id":"e-1","name":"Arun & Priya Wedding","slug":"a","eventDate":"2026-12-12","status":"UPCOMING",
                          "galleryUrl":"x","readyPhotos":0,"createdAt":"2026-02-01T00:00:00Z"}]""");
            }
        });
        server.start();
        client = new StudioClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Test
    void loginReturnsTheTokenAndSendsEmailAndPassword() throws Exception {
        ApiModels.LoginResponse r = client.login("hello@lenslight.test", "s3cret");
        assertThat(r.token()).isEqualTo("jwt-1");
        assertThat(r.email()).isEqualTo("hello@lenslight.test");
        assertThat(requests.get(0)).contains("\"email\":\"hello@lenslight.test\"").contains("\"password\":\"s3cret\"");
    }

    @Test
    void wrongPasswordIsOneClearMessage() {
        loginStatus = 401;
        loginBody = "{\"code\":\"INVALID_CREDENTIALS\",\"message\":\"Invalid email or password\"}";
        assertThatThrownBy(() -> client.login("hello@lenslight.test", "bad"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(401);
                    assertThat(e.code()).isEqualTo("INVALID_CREDENTIALS");
                    assertThat(e.getMessage()).isEqualTo("Email or password is wrong");
                });
    }

    @Test
    void aDisabledAccountShowsTheServersMessage() {
        loginStatus = 403;
        loginBody = "{\"code\":\"ACCOUNT_DISABLED\",\"message\":\"This account is disabled. Contact McreatiK.\"}";
        assertThatThrownBy(() -> client.login("hello@lenslight.test", "x"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("ACCOUNT_DISABLED");
                    assertThat(e.getMessage()).isEqualTo("This account is disabled. Contact McreatiK.");
                });
    }

    @Test
    void mustChangePasswordAsksForTheWebsiteFirst() {
        loginBody = loginBody.replace("\"mustChangePassword\":false", "\"mustChangePassword\":true");
        assertThatThrownBy(() -> client.login("hello@lenslight.test", "x"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(StudioClient.MUST_CHANGE_PASSWORD);
                    assertThat(e.getMessage()).isEqualTo("Set your new password on the website first");
                });
    }

    @Test
    void tooManyTriesIsFriendly() {
        loginStatus = 429;
        loginBody = "";
        assertThatThrownBy(() -> client.login("hello@lenslight.test", "x"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.kind()).isEqualTo(ApiException.Kind.TRANSIENT);
                    assertThat(e.getMessage()).isEqualTo("Too many tries, wait a minute");
                });
    }

    @Test
    void noServerMeansNoInternet() {
        server.stop(0);
        assertThatThrownBy(() -> client.login("hello@lenslight.test", "x"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).isEqualTo(StudioClient.NO_INTERNET));
    }

    @Test
    void eventsAreListedWithTheBearerToken() throws Exception {
        List<ApiModels.StudioEvent> events = client.events("jwt-1");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).name()).isEqualTo("Arun & Priya Wedding");
        assertThat(events.get(0).status()).isEqualTo("UPCOMING");
        assertThat(events.get(0).eventDate()).isEqualTo("2026-12-12");
    }

    @Test
    void anExpiredTokenAsksToSignInAgain() {
        assertThatThrownBy(() -> client.events("old-jwt")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(StudioClient.SESSION_EXPIRED));
    }

    @Test
    void createUploaderReturnsTheSameTokenAConnectionCodeCarries() throws Exception {
        ApiModels.UploaderCredentials c = client.createUploader("jwt-1", "e-1", "LENS-PC");
        assertThat(c.token()).isEqualTo("mku_abc");
        assertThat(requests.get(0)).startsWith("POST /api/admin/events/e-1/uploaders auth=Bearer jwt-1")
                .contains("\"name\":\"LENS-PC\"");
    }

    @Test
    void theServerCanBeOverriddenForTests() {
        System.setProperty("mcreatik.server", "http://localhost:1234");
        try {
            assertThat(StudioClient.resolveServer("https://saved")).isEqualTo("http://localhost:1234");
        } finally {
            System.clearProperty("mcreatik.server");
        }
        assertThat(StudioClient.resolveServer("https://saved")).isEqualTo("https://saved");
        assertThat(StudioClient.resolveServer(null)).isEqualTo("https://im-api.mcreatik.com");
    }
}
