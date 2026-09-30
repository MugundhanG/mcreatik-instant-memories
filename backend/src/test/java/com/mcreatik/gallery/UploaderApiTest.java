package com.mcreatik.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
                .andExpect(jsonPath("$.galleryUrl").value("https://gallery.mcreatik.test/e/" + slug(event)));
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
}
