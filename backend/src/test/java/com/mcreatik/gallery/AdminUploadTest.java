package com.mcreatik.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.mcreatik.gallery.common.Tokens;
import com.mcreatik.gallery.support.IntegrationTest;

/** Admins upload photos from the dashboard; the browser sends ready-made gallery and thumbnail JPEGs. */
class AdminUploadTest extends IntegrationTest {

    private ResultActions upload(String token, String eventId, String fileName, byte[] original, byte[] web,
                                 byte[] thumb) throws Exception {
        return mvc.perform(multipart("/api/admin/events/" + eventId + "/photos")
                .file(new MockMultipartFile("web", "web.jpg", "image/jpeg", web))
                .file(new MockMultipartFile("thumbnail", "thumb.jpg", "image/jpeg", thumb))
                .param("fileName", fileName).param("fileSize", String.valueOf(original.length))
                .param("mimeType", "image/jpeg").param("checksumSha256", Tokens.sha256Hex(original))
                .param("width", "6000").param("height", "4000").param("capturedAt", "2026-10-10T08:15:00Z")
                .header("Authorization", bearer(token)));
    }

    @Test
    void uploadedPhotoIsInTheGuestGalleryAtOnce() throws Exception {
        String event = createEvent("Delivery", "COMPLETED");
        String eventId = eventId(event);
        byte[] original = jpeg(1200, 800);
        upload(adminToken(), eventId, "IMG_0001.JPG", original, jpeg(1024, 683), jpeg(640, 427))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UPLOAD"));

        String photos = publicPhotos(slug(event));
        assertThat(JsonPath.<List<Object>>read(photos, "$.photos")).hasSize(1);
        assertThat(JsonPath.<Integer>read(photos, "$.photos[0].width")).isEqualTo(6000);
        // The event keeps its status: uploading after the event does not make it Live again.
        mvc.perform(get("/api/admin/events/" + eventId).header("Authorization", bearer(adminToken())))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(get("/api/admin/events/" + eventId + "/photos").header("Authorization", bearer(adminToken())))
                .andExpect(jsonPath("$.photos[0].originalFileName").value("IMG_0001.JPG"))
                .andExpect(jsonPath("$.photos[0].status").value("READY"))
                .andExpect(jsonPath("$.photos[0].capturedAt").value("2026-10-10T08:15:00Z"));

        // The same photo again is skipped.
        upload(adminToken(), eventId, "copy.jpg", original, jpeg(1024, 683), jpeg(640, 427))
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        assertThat(JsonPath.<List<Object>>read(publicPhotos(slug(event)), "$.photos")).hasSize(1);
    }

    @Test
    void onlyJpegVersionsAreAccepted() throws Exception {
        String eventId = eventId(createEvent("Bad", "LIVE"));
        upload(adminToken(), eventId, "x.jpg", jpeg(100, 100), "not an image".getBytes(), jpeg(64, 64))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PREPARED"));
    }

    @Test
    void studiosCannotUploadToOtherStudiosEvents() throws Exception {
        String eventId = eventId(createEvent("McreatiK only", "LIVE"));
        mvc.perform(post("/api/admin/studios").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Other\",\"email\":\"other-upload@client.test\",\"password\":\"temporary-123\"}"))
                .andExpect(status().isCreated());
        String studio = login("other-upload@client.test", "temporary-123");
        upload(studio, eventId, "x.jpg", jpeg(100, 100), jpeg(100, 100), jpeg(64, 64))
                .andExpect(status().isNotFound());
    }
}
