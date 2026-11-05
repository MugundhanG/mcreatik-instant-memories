package com.mcreatik.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;
import com.mcreatik.gallery.common.Tokens;
import com.mcreatik.gallery.support.IntegrationTest;

class UploaderApiTest extends IntegrationTest {

    @Test
    void registerUploaderReturnsTokenOnceAndStoresOnlyItsHash() throws Exception {
        String eventId = eventId(createEvent("Arun & Priya", "UPCOMING"));
        String body = mvc.perform(post("/api/admin/events/" + eventId + "/uploaders")
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Camera 1\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.token");
        String code = JsonPath.read(body, "$.connectionCode");

        assertThat(token).startsWith("mku_").hasSizeGreaterThan(40);
        String decoded = new String(Base64.getUrlDecoder().decode(code.substring("MCK1.".length())), StandardCharsets.UTF_8);
        assertThat(decoded).contains("\"server\":\"http://localhost:8080\"").contains(token);

        String stored = jdbc.queryForObject("select token_hash from uploaders", String.class);
        assertThat(stored).isEqualTo(Tokens.sha256Hex(token)).doesNotContain(token);
    }

    @Test
    void uploaderAuthenticatesAndSeesItsEvent() throws Exception {
        String event = createEvent("Arun & Priya", "UPCOMING");
        String token = createUploader(eventId(event), "Camera 1");
        mvc.perform(get("/api/uploader/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploaderName").value("Camera 1"))
                .andExpect(jsonPath("$.eventId").value(eventId(event)))
                .andExpect(jsonPath("$.galleryUrl").value("https://gallery.mcreatik.test/e/" + slug(event)))
                .andExpect(jsonPath("$.eventDate").value("2026-10-10")); // shown in the uploader's event card
        mvc.perform(get("/api/uploader/me").header("Authorization", "Bearer mku_invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void heartbeatMakesUploaderOnlineAndReportsQueue() throws Exception {
        String eventId = eventId(createEvent("Heartbeat", "LIVE"));
        String token = createUploader(eventId, "Camera 1");
        createUploader(eventId, "Camera 2");

        mvc.perform(post("/api/uploader/heartbeat").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceIdentifier\":\"laptop-a\",\"queuePending\":4,\"queueFailed\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptingUploads").value(true));

        mvc.perform(get("/api/admin/events/" + eventId + "/stats").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeUploaders").value(1))
                .andExpect(jsonPath("$.uploaders[0].name").value("Camera 1"))
                .andExpect(jsonPath("$.uploaders[0].status").value("ONLINE"))
                .andExpect(jsonPath("$.uploaders[0].queuePending").value(4))
                .andExpect(jsonPath("$.uploaders[0].deviceIdentifier").value("laptop-a"))
                .andExpect(jsonPath("$.uploaders[1].status").value("OFFLINE"));
    }

    @Test
    void heartbeatWithErrorShowsErrorStatus() throws Exception {
        String eventId = eventId(createEvent("Errors", "LIVE"));
        String token = createUploader(eventId, "Camera 1");
        mvc.perform(post("/api/uploader/heartbeat").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"queuePending\":0,\"queueFailed\":2,\"lastError\":\"Disk not readable\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/events/" + eventId + "/stats").header("Authorization", bearer(adminToken())))
                .andExpect(jsonPath("$.uploaders[0].status").value("ERROR"))
                .andExpect(jsonPath("$.uploaders[0].lastError").value("Disk not readable"));
    }

    @Test
    void rotatingTokenRevokesTheOldOne() throws Exception {
        String eventId = eventId(createEvent("Rotate", "LIVE"));
        String oldToken = createUploader(eventId, "Camera 1");
        String uploaderId = jdbc.queryForObject("select id::text from uploaders", String.class);

        String body = mvc.perform(post("/api/admin/uploaders/" + uploaderId + "/rotate-token")
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String newToken = JsonPath.read(body, "$.token");

        mvc.perform(get("/api/uploader/me").header("Authorization", bearer(oldToken))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/uploader/me").header("Authorization", bearer(newToken))).andExpect(status().isOk());
    }

    @Test
    void deletingUploaderRevokesAccessButKeepsPhotos() throws Exception {
        String eventId = eventId(createEvent("Delete uploader", "LIVE"));
        String token = createUploader(eventId, "Camera 1");
        uploadPhoto(token, "IMG_1.JPG", jpeg(400, 300));
        String uploaderId = jdbc.queryForObject("select id::text from uploaders", String.class);

        mvc.perform(delete("/api/admin/uploaders/" + uploaderId).header("Authorization", bearer(adminToken())))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/uploader/me").header("Authorization", bearer(token))).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from photos", Integer.class)).isEqualTo(1);
    }

    // ---- "Add camera" / "Remove camera" from the uploader app ----

    private String addCamera(String token, int expectedStatus) throws Exception {
        var r = mvc.perform(post("/api/uploader/cameras").header("Authorization", bearer(token)))
                .andExpect(status().is(expectedStatus)).andReturn();
        return expectedStatus == 201 ? r.getResponse().getContentAsString() : null;
    }

    @Test
    void theAppAddsCamerasToItsOwnEventNamedInOrder() throws Exception {
        String event = createEvent("Two shooters", "LIVE");
        String token = createUploader(eventId(event), "Camera 1");

        String second = addCamera(token, 201);
        assertThat(JsonPath.<String>read(second, "$.name")).isEqualTo("Camera 2");
        String secondToken = JsonPath.read(second, "$.token");
        mvc.perform(get("/api/uploader/me").header("Authorization", bearer(secondToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId(event)))
                .andExpect(jsonPath("$.uploaderName").value("Camera 2"));

        String third = addCamera(secondToken, 201); // any camera of the event may add the next one
        assertThat(JsonPath.<String>read(third, "$.name")).isEqualTo("Camera 3");
        assertThat(jdbc.queryForObject("select count(*) from uploaders where event_id = ?::uuid", Integer.class,
                eventId(event))).isEqualTo(3);
        // never into another event
        String other = createEvent("Other", "LIVE");
        assertThat(jdbc.queryForObject("select count(*) from uploaders where event_id = ?::uuid", Integer.class,
                eventId(other))).isZero();
    }

    @Test
    void anEventHasAtMostEightCameras() throws Exception {
        String token = createUploader(eventId(createEvent("Big", "LIVE")), "Camera 1");
        for (int i = 2; i <= 8; i++) {
            addCamera(token, 201);
        }
        mvc.perform(post("/api/uploader/cameras").header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOO_MANY_CAMERAS"));
    }

    @Test
    void noCamerasAreAddedToAnArchivedEvent() throws Exception {
        String event = createEvent("Old", "LIVE");
        String token = createUploader(eventId(event), "Camera 1");
        jdbc.update("update events set status = 'ARCHIVED' where id = ?::uuid", eventId(event));
        mvc.perform(post("/api/uploader/cameras").header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_ARCHIVED"));
    }

    @Test
    void aRemovedCameraLeavesTheEventAndItsTokenStopsWorking() throws Exception {
        String event = createEvent("Remove", "LIVE");
        String token = createUploader(eventId(event), "Camera 1");
        String secondToken = JsonPath.read(addCamera(token, 201), "$.token");
        mvc.perform(delete("/api/uploader/me").header("Authorization", bearer(secondToken)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/uploader/me").header("Authorization", bearer(secondToken)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/uploader/me").header("Authorization", bearer(token))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from uploaders where event_id = ?::uuid", Integer.class,
                eventId(event))).isEqualTo(1);
        mvc.perform(post("/api/uploader/cameras")).andExpect(status().isUnauthorized());
    }

    // ---- camera names and the privacy pause ----

    @Test
    void theAppNamesANewCameraAndNamesAreUniqueInAnEvent() throws Exception {
        String token = createUploader(eventId(createEvent("Names", "LIVE")), "Main");
        String body = mvc.perform(post("/api/uploader/cameras").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  Candid   Divya \"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(body, "$.name")).isEqualTo("Candid Divya");
        mvc.perform(post("/api/uploader/cameras").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"candid divya\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMERA_NAME_TAKEN"));
        // a blank name still gets the next "Camera N"
        String third = mvc.perform(post("/api/uploader/cameras").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(third, "$.name")).isEqualTo("Camera 3");
    }

    @Test
    void theDashboardRenamesAndPausesACameraAndTheAppSeesIt() throws Exception {
        String eventId = eventId(createEvent("Pause", "LIVE"));
        String token = createUploader(eventId, "Camera 1");
        createUploader(eventId, "Drone");
        String uploaderId = jdbc.queryForObject("select id::text from uploaders where name = 'Camera 1'", String.class);

        mvc.perform(patch("/api/admin/uploaders/" + uploaderId).header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"drone\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CAMERA_NAME_TAKEN"));
        mvc.perform(patch("/api/admin/uploaders/" + uploaderId).header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Main - Karthik\",\"paused\":true}"))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/uploader/heartbeat").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"queuePending\":0,\"queueFailed\":0}"))
                .andExpect(jsonPath("$.paused").value(true))
                .andExpect(jsonPath("$.uploaderName").value("Main - Karthik"));
        mvc.perform(get("/api/uploader/me").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.paused").value(true));
        mvc.perform(get("/api/admin/events/" + eventId + "/stats").header("Authorization", bearer(adminToken())))
                .andExpect(jsonPath("$.uploaders[0].paused").value(true))
                .andExpect(jsonPath("$.uploaders[1].paused").value(false));

        // Nothing from a paused camera reaches the gallery, whatever the app believes.
        mvc.perform(post("/api/uploader/uploads").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(uploadRequestJson("IMG_9.JPG", jpeg(300, 200))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UPLOADER_PAUSED"));
        assertThat(jdbc.queryForObject("select count(*) from photos", Integer.class)).isZero();

        // The app resumes it itself; uploads work again.
        mvc.perform(patch("/api/uploader/me").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paused\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paused").value(false))
                .andExpect(jsonPath("$.name").value("Main - Karthik"));
        uploadPhoto(token, "IMG_10.JPG", jpeg(300, 200));
        assertThat(jdbc.queryForObject("select count(*) from photos", Integer.class)).isEqualTo(1);
    }

    @Test
    void aStudioCantRenameOrPauseAnotherStudiosCamera() throws Exception {
        String eventId = eventId(createEvent("Mine", "LIVE"));
        createUploader(eventId, "Camera 1");
        String uploaderId = jdbc.queryForObject("select id::text from uploaders", String.class);
        String email = "other-" + java.util.UUID.randomUUID().toString().substring(0, 6) + "@studio.test";
        mvc.perform(post("/api/admin/studios").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Other\",\"email\":\"" + email + "\",\"password\":\"temporary-123\",\"plan\":\"PER_EVENT\"}"))
                .andExpect(status().isCreated());
        String otherStudio = JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"temporary-123\"}")
                        .with(r -> {
                            r.setRemoteAddr("10.88.0.7");
                            return r;
                        }))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
        mvc.perform(patch("/api/admin/uploaders/" + uploaderId).header("Authorization", bearer(otherStudio))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paused\":true}"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select paused from uploaders", Boolean.class)).isFalse();
    }
}
