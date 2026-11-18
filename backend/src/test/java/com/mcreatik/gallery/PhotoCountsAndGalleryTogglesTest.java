package com.mcreatik.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.mcreatik.gallery.common.Tokens;
import com.mcreatik.gallery.support.IntegrationTest;

/** Exact per-gallery photo counts, the sliding session, and the studio's own gallery switches. */
class PhotoCountsAndGalleryTogglesTest extends IntegrationTest {

    private ResultActions dashboardUpload(String token, String eventId, String gallery, byte[] original, boolean withOriginal)
            throws Exception {
        return mvc.perform(multipart("/api/admin/events/" + eventId + "/photos")
                .file(new MockMultipartFile("web", "web.jpg", "image/jpeg", jpeg(1024, 683)))
                .file(new MockMultipartFile("thumbnail", "thumb.jpg", "image/jpeg", jpeg(640, 427)))
                .param("fileName", "IMG.JPG").param("fileSize", String.valueOf(original.length))
                .param("mimeType", "image/jpeg").param("checksumSha256", Tokens.sha256Hex(original))
                .param("width", "6000").param("height", "4000").param("gallery", gallery)
                .param("original", String.valueOf(withOriginal)).header("Authorization", bearer(token)));
    }

    private String session(String token, String eventId, byte[] original) throws Exception {
        return mvc.perform(post("/api/admin/events/" + eventId + "/original-uploads")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(uploadRequestJson("IMG.JPG", original).replace("}", ",\"gallery\":\"CLIENT\"}")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void clientPhoto(String token, String eventId, byte[] original) throws Exception {
        putToStorage(JsonPath.read(session(token, eventId, original), "$.upload.url"), original);
        dashboardUpload(token, eventId, "CLIENT", original, true).andExpect(status().isOk());
    }

    @Test
    void countsArePerGalleryReadyOnlyAndRetriesNeverAddRows() throws Exception {
        String token = adminToken();
        String eventId = eventId(createEvent("Counts", "LIVE"));
        byte[] guest = jpeg(900, 600);
        dashboardUpload(token, eventId, "GUEST", guest, false).andExpect(status().isOk());
        dashboardUpload(token, eventId, "GUEST", jpeg(900, 601), false).andExpect(status().isOk());
        byte[] first = jpeg(1000, 700);
        for (int i = 0; i < 3; i++) {
            clientPhoto(token, eventId, i == 0 ? first : jpeg(1000, 701 + i));
        }
        // A client upload that never finished (original session only) and a failed one are not photos yet.
        session(token, eventId, jpeg(1000, 800));
        byte[] failed = jpeg(1000, 900);
        session(token, eventId, failed);
        jdbc.update("UPDATE photos SET status = 'FAILED' WHERE checksum_sha256 = ?", Tokens.sha256Hex(failed));
        // Retrying a finished upload (timeouts, double tabs) is a DUPLICATE: no extra row.
        assertThat(JsonPath.<String>read(session(token, eventId, first), "$.outcome")).isEqualTo("DUPLICATE");
        dashboardUpload(token, eventId, "CLIENT", first, true).andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        dashboardUpload(token, eventId, "GUEST", guest, false).andExpect(jsonPath("$.outcome").value("DUPLICATE"));

        mvc.perform(get("/api/admin/events/" + eventId + "/stats").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.guestPhotos").value(2)).andExpect(jsonPath("$.clientPhotos").value(3))
                .andExpect(jsonPath("$.readyPhotos").value(5)).andExpect(jsonPath("$.totalPhotos").value(7));
        mvc.perform(get("/api/admin/events").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$[0].guestPhotos").value(2)).andExpect(jsonPath("$[0].clientPhotos").value(3))
                .andExpect(jsonPath("$[0].readyPhotos").value(5));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM photos WHERE gallery = 'CLIENT' AND status = 'READY'", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void aLongSessionIsExtendedByRefreshingTheToken() throws Exception {
        String token = adminToken();
        String body = mvc.perform(post("/api/admin/account/refresh").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(ADMIN_EMAIL))
                .andReturn().getResponse().getContentAsString();
        String fresh = JsonPath.read(body, "$.token");
        assertThat(JsonPath.<String>read(body, "$.expiresAt")).isNotBlank();
        mvc.perform(get("/api/admin/account").header("Authorization", bearer(fresh))).andExpect(status().isOk());
        mvc.perform(post("/api/admin/account/refresh")).andExpect(status().isUnauthorized());
    }

    private String studioToken(String name) throws Exception {
        String email = name.replace(" ", "") + "-" + UUID.randomUUID().toString().substring(0, 6) + "@studio.test";
        mvc.perform(post("/api/admin/studios").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"temporary-123\",\"plan\":\"UNLIMITED\"}"))
                .andExpect(status().isCreated());
        return login(email, "temporary-123");
    }

    private ResultActions access(String token, String eventId, String body) throws Exception {
        return mvc.perform(patch("/api/admin/events/" + eventId + "/access").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String studioEvent(String studio) throws Exception {
        String r = mvc.perform(post("/api/admin/events").header("Authorization", bearer(studio))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Switches\",\"eventDate\":\"2026-12-01\",\"status\":\"LIVE\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return eventId(r);
    }

    @Test
    void aStudioTurnsItsGalleriesOffAndOnButBillingAndMcreatikLocksHold() throws Exception {
        String studio = studioToken("Switch Studio");
        String eventId = studioEvent(studio);
        // Off and on again by the studio.
        access(studio, eventId, "{\"liveGallery\":false,\"clientGallery\":false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.liveGalleryEnabled").value(false)).andExpect(jsonPath("$.clientGalleryEnabled").value(false))
                .andExpect(jsonPath("$.liveGalleryBlocked").doesNotExist());
        access(studio, eventId, "{\"liveGallery\":true,\"clientGallery\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.liveGalleryEnabled").value(true));
        // Another studio cannot touch it.
        access(studioToken("Other Studio"), eventId, "{\"liveGallery\":false}").andExpect(status().isNotFound());

        // The paid period ended: both stay off, with the reason.
        access(studio, eventId, "{\"liveGallery\":false,\"clientGallery\":false}").andExpect(status().isOk());
        jdbc.update("UPDATE events SET active_until = '2020-01-01' WHERE id = ?", UUID.fromString(eventId));
        access(studio, eventId, "{\"liveGallery\":true}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GALLERY_CANNOT_TURN_ON"));
        mvc.perform(get("/api/admin/events/" + eventId).header("Authorization", bearer(studio)))
                .andExpect(jsonPath("$.liveGalleryBlocked").isNotEmpty()).andExpect(jsonPath("$.clientGalleryBlocked").isNotEmpty());
        // Extended again: allowed; unpaid storage still blocks only the client gallery.
        jdbc.update("UPDATE events SET active_until = '2099-01-01', storage_locked = true WHERE id = ?", UUID.fromString(eventId));
        access(studio, eventId, "{\"liveGallery\":true}").andExpect(status().isOk());
        access(studio, eventId, "{\"clientGallery\":true}").andExpect(status().isConflict());
        jdbc.update("UPDATE events SET storage_locked = false WHERE id = ?", UUID.fromString(eventId));
        access(studio, eventId, "{\"clientGallery\":true}").andExpect(status().isOk());

        // McreatiK turns one off: the studio cannot turn it back on (and cannot clear the lock by turning it off).
        access(adminToken(), eventId, "{\"liveGallery\":false}").andExpect(status().isOk());
        access(studio, eventId, "{\"liveGallery\":false}").andExpect(status().isOk());
        access(studio, eventId, "{\"liveGallery\":true}").andExpect(status().isConflict());
        access(adminToken(), eventId, "{\"liveGallery\":true}").andExpect(status().isOk());
        access(studio, eventId, "{\"liveGallery\":false}").andExpect(status().isOk());
        access(studio, eventId, "{\"liveGallery\":true}").andExpect(status().isOk());
    }

    @Test
    void uploadsStillWorkWhileAGalleryIsOff() throws Exception {
        String studio = studioToken("Off Uploads");
        String eventId = studioEvent(studio);
        access(studio, eventId, "{\"liveGallery\":false}").andExpect(status().isOk());
        dashboardUpload(studio, eventId, "GUEST", jpeg(900, 600), false).andExpect(status().isOk());
        String slug = jdbc.queryForObject("SELECT slug FROM events WHERE id = ?", String.class, UUID.fromString(eventId));
        mvc.perform(get("/api/public/events/" + slug + "/photos")).andExpect(status().isForbidden());
    }
}
