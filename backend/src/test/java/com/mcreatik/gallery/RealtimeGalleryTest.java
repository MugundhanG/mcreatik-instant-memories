package com.mcreatik.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.jayway.jsonpath.JsonPath;
import com.mcreatik.gallery.realtime.GalleryBroadcaster;
import com.mcreatik.gallery.support.IntegrationTest;

class RealtimeGalleryTest extends IntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    GalleryBroadcaster broadcaster;

    @Test
    void connectedGuestReceivesPhotoReadyWithoutPolling() throws Exception {
        String event = createEvent("Live Test", "LIVE");
        String eventId = eventId(event);
        String token = createUploader(eventId, "Camera 1");

        List<String> lines = new CopyOnWriteArrayList<>();
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<java.io.InputStream> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/public/events/" + slug(event) + "/stream"))
                .header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("text/event-stream");

        Thread reader = Thread.ofVirtual().start(() -> {
            try (var in = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    lines.add(line);
                }
            } catch (Exception ignored) {
                // stream closed at end of test
            }
        });

        await().atMost(Duration.ofSeconds(5)).until(() -> lines.contains("event:CONNECTED"));
        assertThat(broadcaster.subscriberCount(java.util.UUID.fromString(eventId))).isEqualTo(1);

        String photoId = uploadPhoto(token, "IMG_0042.JPG", jpeg(1200, 800));
        processAll();

        await().atMost(Duration.ofSeconds(5)).until(() -> lines.contains("event:PHOTO_READY"));
        int eventLine = lines.indexOf("event:PHOTO_READY");
        String data = lines.subList(eventLine, lines.size()).stream()
                .filter(l -> l.startsWith("data:")).findFirst().orElseThrow();
        String json = data.substring("data:".length());
        assertThat((String) JsonPath.read(json, "$.photoId")).isEqualTo(photoId);
        assertThat((String) JsonPath.read(json, "$.eventId")).isEqualTo(eventId);
        assertThat((String) JsonPath.read(json, "$.thumbnailUrl")).contains("/thumb/");
        assertThat((String) JsonPath.read(json, "$.webUrl")).contains("/web/");
        assertThat((Object) JsonPath.read(json, "$.readyAt")).isNotNull();
        assertThat((Object) JsonPath.read(json, "$.cursor")).isNotNull();

        reader.interrupt();
    }

    @Test
    void streamIsNotAvailableForDraftEvents() throws Exception {
        String event = createEvent("Draft", "DRAFT");
        HttpClient http = HttpClient.newHttpClient();
        var response = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/public/events/" + slug(event) + "/stream")).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(404);
    }
}
