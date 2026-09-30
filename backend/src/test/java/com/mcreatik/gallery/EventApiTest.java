package com.mcreatik.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;
import com.mcreatik.gallery.support.IntegrationTest;

class EventApiTest extends IntegrationTest {

    @Test
    void createEventGeneratesUnguessableSlugGalleryUrlAndRetention() throws Exception {
        String json = createEvent("Arun & Priya Wedding", "UPCOMING");
        String slug = slug(json);
        assertThat(slug).matches("arun-priya-wedding-[a-z0-9]{4}");
        assertThat((String) JsonPath.read(json, "$.galleryUrl")).isEqualTo("https://gallery.mcreatik.test/e/" + slug);
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("UPCOMING");
        assertThat((String) JsonPath.read(json, "$.retentionUntil")).isEqualTo("2027-01-08");
    }

    @Test
    void createEventValidatesInput() throws Exception {
        mvc.perform(post("/api/admin/events").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/admin/events").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"eventDate\":\"2026-10-10\",\"slug\":\"Bad Slug!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SLUG"));
    }

    @Test
    void customSlugMustBeUnique() throws Exception {
        String body = "{\"name\":\"A\",\"eventDate\":\"2026-10-10\",\"slug\":\"arun-priya\"}";
        mvc.perform(post("/api/admin/events").header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post("/api/admin/events").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLUG_TAKEN"));
    }

    @Test
    void retrieveListAndUpdateEvent() throws Exception {
        String id = eventId(createEvent("Corporate Summit", "DRAFT"));
        mvc.perform(get("/api/admin/events/" + id).header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Corporate Summit"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
        mvc.perform(get("/api/admin/events").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].readyPhotos").value(0));
        mvc.perform(patch("/api/admin/events/" + id).header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LIVE\",\"name\":\"Summit 2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LIVE"))
                .andExpect(jsonPath("$.name").value("Summit 2026"));
    }

    @Test
    void publicGalleryVisibilityFollowsStatus() throws Exception {
        String json = createEvent("Birthday", "DRAFT");
        String slug = slug(json);
        mvc.perform(get("/api/public/events/" + slug)).andExpect(status().isNotFound());

        mvc.perform(patch("/api/admin/events/" + eventId(json)).header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LIVE\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/public/events/" + slug)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Birthday"))
                .andExpect(jsonPath("$.live").value(true))
                .andExpect(jsonPath("$.photoCount").value(0));

        mvc.perform(patch("/api/admin/events/" + eventId(json)).header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ARCHIVED\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/public/events/" + slug)).andExpect(status().isNotFound());
    }

    @Test
    void deleteEventRemovesEverything() throws Exception {
        String json = createEvent("Delete Me", "LIVE");
        String id = eventId(json);
        String token = createUploader(id, "Camera 1");
        uploadPhoto(token, "IMG_001.JPG", jpeg(800, 600));
        processAll();

        mvc.perform(delete("/api/admin/events/" + id).header("Authorization", bearer(adminToken())))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from photos", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from uploaders", Integer.class)).isZero();
        mvc.perform(get("/api/public/events/" + slug(json))).andExpect(status().isNotFound());
        mvc.perform(get("/api/uploader/me").header("Authorization", bearer(token))).andExpect(status().isUnauthorized());
    }
}
