package com.mcreatik.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;
import com.mcreatik.gallery.common.Tokens;
import com.mcreatik.gallery.support.IntegrationTest;

class UploadFlowTest extends IntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void fullUploadProcessingAndPublicRetrieval() throws Exception {
        String event = createEvent("Arun & Priya", "UPCOMING");
        String token = createUploader(eventId(event), "Camera 1");
        byte[] photo = jpeg(3000, 2000);

        String photoId = uploadPhoto(token, "C:\\\\DCIM\\\\IMG_0001.JPG", photo);
        assertThat(jdbc.queryForObject("select status from photos", String.class)).isEqualTo("UPLOADED");
        // First upload switches an UPCOMING event to LIVE automatically.
        assertThat(jdbc.queryForObject("select status from events", String.class)).isEqualTo("LIVE");

        processAll();

        var row = jdbc.queryForMap("select * from photos where id = ?::uuid", photoId);
        assertThat(row.get("status")).isEqualTo("READY");
        assertThat(row.get("original_file_name")).isEqualTo("IMG_0001.JPG");
        assertThat(row.get("width")).isEqualTo(3000);
        assertThat(row.get("height")).isEqualTo(2000);
        assertThat(row.get("processed_at")).isNotNull();

        String page = publicPhotos(slug(event));
        assertThat((List<?>) JsonPath.read(page, "$.photos")).hasSize(1);
        String thumbUrl = JsonPath.read(page, "$.photos[0].thumbnailUrl");
        String webUrl = JsonPath.read(page, "$.photos[0].webUrl");
        assertThat(page).doesNotContain("originals").doesNotContain("uploaderId");

        byte[] thumb = mvc.perform(get(URI.create(thumbUrl).getPath())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        byte[] web = mvc.perform(get(URI.create(webUrl).getPath())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        var thumbImg = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(thumb));
        var webImg = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(web));
        assertThat(thumbImg.getWidth()).isEqualTo(640);
        assertThat(webImg.getWidth()).isEqualTo(2048);
        assertThat(thumb.length).isLessThan(web.length);
    }

    @Test
    void duplicateUploadIsDetectedWithoutSendingBytesAgain() throws Exception {
        String eventId = eventId(createEvent("Dup", "LIVE"));
        String token = createUploader(eventId, "Camera 1");
        byte[] photo = jpeg(640, 480);
        String first = uploadPhoto(token, "IMG_1.JPG", photo);

        mvc.perform(post("/api/uploader/uploads").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(uploadRequestJson("IMG_1_copy.JPG", photo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"))
                .andExpect(jsonPath("$.photoId").value(first))
                .andExpect(jsonPath("$.upload").doesNotExist());

        // Same file from a different camera is still a duplicate within the event.
        String token2 = createUploader(eventId, "Camera 2");
        mvc.perform(post("/api/uploader/uploads").header("Authorization", bearer(token2))
                        .contentType(MediaType.APPLICATION_JSON).content(uploadRequestJson("IMG_1.JPG", photo)))
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        assertThat(jdbc.queryForObject("select count(*) from photos", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameFileInDifferentEventsIsNotADuplicate() throws Exception {
        byte[] photo = jpeg(640, 480);
        uploadPhoto(createUploader(eventId(createEvent("A", "LIVE")), "Cam"), "IMG.JPG", photo);
        uploadPhoto(createUploader(eventId(createEvent("B", "LIVE")), "Cam"), "IMG.JPG", photo);
        assertThat(jdbc.queryForObject("select count(*) from photos", Integer.class)).isEqualTo(2);
    }

    @Test
    void interruptedUploadResumesWithSamePhotoId() throws Exception {
        String token = createUploader(eventId(createEvent("Resume", "LIVE")), "Camera 1");
        byte[] photo = jpeg(640, 480);
        String firstSession = mvc.perform(post("/api/uploader/uploads").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(uploadRequestJson("IMG.JPG", photo)))
                .andReturn().getResponse().getContentAsString();
        // Uploader crashed before PUT. On restart it asks again:
        String second = mvc.perform(post("/api/uploader/uploads").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(uploadRequestJson("IMG.JPG", photo)))
                .andExpect(jsonPath("$.outcome").value("UPLOAD"))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(second, "$.photoId")).isEqualTo(JsonPath.read(firstSession, "$.photoId"));
    }

    @Test
    void completeBeforeFileArrivesIsRejectedAndCompleteIsIdempotent() throws Exception {
        String token = createUploader(eventId(createEvent("Complete", "LIVE")), "Camera 1");
        byte[] photo = jpeg(640, 480);
        String session = mvc.perform(post("/api/uploader/uploads").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(uploadRequestJson("IMG.JPG", photo)))
                .andReturn().getResponse().getContentAsString();
        String photoId = JsonPath.read(session, "$.photoId");

        mvc.perform(post("/api/uploader/uploads/" + photoId + "/complete").header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPLOAD_NOT_FOUND"));

        putToStorage(JsonPath.read(session, "$.upload.url"), photo);
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/uploader/uploads/" + photoId + "/complete").header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UPLOADED"));
        }
    }

    @Test
    void uploaderCannotCompleteAnotherEventsPhoto() throws Exception {
        String tokenA = createUploader(eventId(createEvent("A", "LIVE")), "Cam A");
        String tokenB = createUploader(eventId(createEvent("B", "LIVE")), "Cam B");
        String photoId = uploadPhoto(tokenA, "IMG.JPG", jpeg(320, 240));
        mvc.perform(post("/api/uploader/uploads/" + photoId + "/complete").header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadValidationRejectsBadTypesSizesAndChecksums() throws Exception {
        String token = createUploader(eventId(createEvent("Validation", "LIVE")), "Camera 1");
        String sha = "a".repeat(64);
        mvc.perform(post("/api/uploader/uploads").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"x.exe\",\"fileSize\":10,\"mimeType\":\"application/x-msdownload\",\"checksumSha256\":\"" + sha + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_TYPE"));
        mvc.perform(post("/api/uploader/uploads").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"x.jpg\",\"fileSize\":999999999999,\"mimeType\":\"image/jpeg\",\"checksumSha256\":\"" + sha + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
        mvc.perform(post("/api/uploader/uploads").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"x.jpg\",\"fileSize\":10,\"mimeType\":\"image/jpeg\",\"checksumSha256\":\"" + "z".repeat(64) + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CHECKSUM"));
    }

    @Test
    void archivedEventRejectsUploads() throws Exception {
        String eventId = eventId(createEvent("Closed", "ARCHIVED"));
        String token = createUploader(eventId, "Camera 1");
        mvc.perform(post("/api/uploader/uploads").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(uploadRequestJson("IMG.JPG", jpeg(100, 100))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_CLOSED"));
    }

    @Test
    void fileWithImageExtensionButNonImageContentFailsProcessing() throws Exception {
        String token = createUploader(eventId(createEvent("Fake", "LIVE")), "Camera 1");
        byte[] notAnImage = "MZ this is actually an executable".getBytes();
        uploadPhoto(token, "IMG.JPG", notAnImage);
        processAll();
        var row = jdbc.queryForMap("select status, failure_reason from photos");
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat((String) row.get("failure_reason")).contains("Not a JPEG or PNG");
    }

    @Test
    void corruptedTransferFailsChecksumVerification() throws Exception {
        String token = createUploader(eventId(createEvent("Corrupt", "LIVE")), "Camera 1");
        byte[] photo = jpeg(640, 480);
        String session = mvc.perform(post("/api/uploader/uploads").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(uploadRequestJson("IMG.JPG", photo)))
                .andReturn().getResponse().getContentAsString();
        byte[] corrupted = photo.clone();
        corrupted[corrupted.length / 2] ^= 0x55;
        putToStorage(JsonPath.read(session, "$.upload.url"), corrupted);
        mvc.perform(post("/api/uploader/uploads/" + JsonPath.read(session, "$.photoId") + "/complete")
                .header("Authorization", bearer(token))).andExpect(status().isOk());
        processAll();
        assertThat(jdbc.queryForObject("select failure_reason from photos", String.class)).contains("Checksum mismatch");

        // The uploader retries the same file: a FAILED photo can be re-sent.
        mvc.perform(post("/api/uploader/uploads").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(uploadRequestJson("IMG.JPG", photo)))
                .andExpect(jsonPath("$.outcome").value("UPLOAD"));
    }

    @Test
    void tamperedPresignedUrlIsRejected() throws Exception {
        String token = createUploader(eventId(createEvent("Tamper", "LIVE")), "Camera 1");
        byte[] photo = jpeg(320, 240);
        String session = mvc.perform(post("/api/uploader/uploads").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(uploadRequestJson("IMG.JPG", photo)))
                .andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(session, "$.upload.url");
        URI uri = URI.create(url.replace("/originals/", "/web/"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put(uri.getRawPath() + "?" + uri.getRawQuery()).contentType("image/jpeg").content(photo))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicFeedPaginatesNewestFirstAndSupportsCatchUp() throws Exception {
        String event = createEvent("Paging", "LIVE");
        String token = createUploader(eventId(event), "Camera 1");
        for (int i = 0; i < 5; i++) {
            uploadPhoto(token, "IMG_" + i + ".JPG", jpeg(200, 150));
            processAll();
        }
        String slug = slug(event);
        String page1 = mvc.perform(get("/api/public/events/" + slug + "/photos?limit=2"))
                .andReturn().getResponse().getContentAsString();
        List<String> ids1 = JsonPath.read(page1, "$.photos[*].id");
        String next = JsonPath.read(page1, "$.nextCursor");
        String page2 = mvc.perform(get("/api/public/events/" + slug + "/photos?limit=2&before=" + next))
                .andReturn().getResponse().getContentAsString();
        List<String> ids2 = JsonPath.read(page2, "$.photos[*].id");
        assertThat(ids1).hasSize(2).doesNotContainAnyElementsOf(ids2);

        List<String> readyAt = JsonPath.read(publicPhotos(slug), "$.photos[*].readyAt");
        assertThat(readyAt).isSortedAccordingTo(java.util.Comparator.reverseOrder());

        // Catch-up after reconnect: newer than the 3rd-newest photo = the 2 newest, oldest first.
        String cursorOfThird = JsonPath.read(page2, "$.photos[0].cursor");
        String catchUp = mvc.perform(get("/api/public/events/" + slug + "/photos?after=" + cursorOfThird))
                .andReturn().getResponse().getContentAsString();
        List<String> caught = JsonPath.read(catchUp, "$.photos[*].id");
        assertThat(caught).containsExactly(ids1.get(1), ids1.get(0));
    }

    @Test
    void adminSeesStatsPerCameraAndCanDeletePhotosAndSetCover() throws Exception {
        String eventId = eventId(createEvent("Stats", "LIVE"));
        String cam1 = createUploader(eventId, "Camera 1");
        String cam2 = createUploader(eventId, "Camera 2");
        uploadPhoto(cam1, "A1.JPG", jpeg(200, 150));
        uploadPhoto(cam1, "A2.JPG", jpeg(200, 150));
        String b1 = uploadPhoto(cam2, "B1.JPG", jpeg(200, 150));
        uploadPhoto(cam2, "BROKEN.JPG", "not an image".getBytes());
        processAll();

        mvc.perform(get("/api/admin/events/" + eventId + "/stats").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPhotos").value(4))
                .andExpect(jsonPath("$.readyPhotos").value(3))
                .andExpect(jsonPath("$.failedPhotos").value(1))
                .andExpect(jsonPath("$.uploaders[0].photosReady").value(2))
                .andExpect(jsonPath("$.uploaders[1].photosUploaded").value(2))
                .andExpect(jsonPath("$.uploaders[1].photosReady").value(1))
                .andExpect(jsonPath("$.latestPhoto.id").isNotEmpty());

        mvc.perform(patch("/api/admin/events/" + eventId).header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"coverPhotoId\":\"" + b1 + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.coverUrl").isNotEmpty());

        mvc.perform(get("/api/admin/photos/" + b1 + "/original").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.containsString("sig=")));

        mvc.perform(delete("/api/admin/photos/" + b1).header("Authorization", bearer(adminToken())))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from photos where status='READY'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select cover_photo_id from events", String.class)).isNull();
    }

    /** Three cameras upload simultaneously over real HTTP; every photo lands in one event exactly once. */
    @Test
    void multipleUploadersUploadConcurrentlyToTheSameEvent() throws Exception {
        String event = createEvent("Arun & Priya Wedding", "LIVE");
        List<String> tokens = List.of(createUploader(eventId(event), "Camera 1"),
                createUploader(eventId(event), "Camera 2"), createUploader(eventId(event), "Camera 3"));
        int perCamera = 8;
        HttpClient http = HttpClient.newHttpClient();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<Integer>> results = new ArrayList<>();
        byte[] sharedDuplicate = jpeg(300, 200);
        for (String token : tokens) {
            for (int worker = 0; worker < 2; worker++) {
                int w = worker;
                results.add(pool.submit((Callable<Integer>) () -> {
                    int uploaded = 0;
                    for (int i = w; i < perCamera; i += 2) {
                        uploadOverHttp(http, token, "IMG_" + i + ".JPG", jpeg(300, 200));
                        uploaded++;
                    }
                    // Every camera also tries the same file at the same time: only one may win.
                    uploadOverHttp(http, token, "SAME.JPG", sharedDuplicate);
                    return uploaded;
                }));
            }
        }
        int total = 0;
        for (Future<Integer> f : results) {
            total += f.get();
        }
        pool.shutdown();
        processAll();

        assertThat(total).isEqualTo(3 * perCamera);
        assertThat(jdbc.queryForObject("select count(*) from photos where status = 'READY'", Integer.class))
                .isEqualTo(3 * perCamera + 1);
        assertThat(jdbc.queryForObject("select count(distinct uploader_id) from photos", Integer.class)).isEqualTo(3);
        assertThat((List<?>) JsonPath.read(mvc.perform(get("/api/public/events/" + slug(event) + "/photos?limit=100"))
                .andReturn().getResponse().getContentAsString(), "$.photos")).hasSize(3 * perCamera + 1);
    }

    private void uploadOverHttp(HttpClient http, String token, String name, byte[] bytes) throws Exception {
        String base = "http://localhost:" + port;
        var session = http.send(HttpRequest.newBuilder(URI.create(base + "/api/uploader/uploads"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(uploadRequestJson(name, bytes))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(session.statusCode()).isEqualTo(200);
        if (!"UPLOAD".equals(JsonPath.read(session.body(), "$.outcome"))) {
            return;
        }
        String url = ((String) JsonPath.read(session.body(), "$.upload.url")).replace("http://localhost:8080", base);
        var put = http.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "image/jpeg")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(put.statusCode()).isEqualTo(200);
        var complete = http.send(HttpRequest.newBuilder(URI.create(base + "/api/uploader/uploads/"
                        + JsonPath.read(session.body(), "$.photoId") + "/complete"))
                .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(complete.statusCode()).isEqualTo(200);
        assertThat(Tokens.sha256Hex(bytes)).hasSize(64);
    }
}
