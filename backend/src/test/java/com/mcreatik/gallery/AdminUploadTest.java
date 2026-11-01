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
        return upload(token, eventId, fileName, original, web, thumb, false);
    }

    private ResultActions upload(String token, String eventId, String fileName, byte[] original, byte[] web,
                                 byte[] thumb, boolean withOriginal) throws Exception {
        return mvc.perform(multipart("/api/admin/events/" + eventId + "/photos")
                .file(new MockMultipartFile("web", "web.jpg", "image/jpeg", web))
                .file(new MockMultipartFile("thumbnail", "thumb.jpg", "image/jpeg", thumb))
                .param("fileName", fileName).param("fileSize", String.valueOf(original.length))
                .param("mimeType", "image/jpeg").param("checksumSha256", Tokens.sha256Hex(original))
                .param("width", "6000").param("height", "4000").param("capturedAt", "2026-10-10T08:15:00Z")
                .param("original", String.valueOf(withOriginal))
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

    private String originalSession(String eventId, byte[] original) throws Exception {
        return mvc.perform(post("/api/admin/events/" + eventId + "/original-uploads")
                        .header("Authorization", bearer(adminToken())).contentType(MediaType.APPLICATION_JSON)
                        .content(uploadRequestJson("IMG_9000.JPG", original)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String originalAvailable(String eventId) throws Exception {
        return mvc.perform(get("/api/admin/events/" + eventId + "/photos").header("Authorization", bearer(adminToken())))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void originalQualityUploadKeepsTheFullFile() throws Exception {
        String eventId = eventId(createEvent("Originals", "COMPLETED"));
        byte[] original = jpeg(1600, 1067);
        String session = originalSession(eventId, original);
        assertThat(JsonPath.<Boolean>read(session, "$.needsVersions")).isTrue();
        // The gallery versions alone are refused until the original is really in storage.
        upload(adminToken(), eventId, "IMG_9000.JPG", original, jpeg(1024, 683), jpeg(640, 427), true)
                .andExpect(status().isConflict());
        putToStorage(JsonPath.read(session, "$.upload.url"), original);
        upload(adminToken(), eventId, "IMG_9000.JPG", original, jpeg(1024, 683), jpeg(640, 427), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UPLOAD"));
        String list = originalAvailable(eventId);
        assertThat(JsonPath.<Boolean>read(list, "$.photos[0].originalAvailable")).isTrue();
        assertThat(JsonPath.<String>read(list, "$.photos[0].status")).isEqualTo("READY");
        // Uploading the same original again changes nothing.
        assertThat(JsonPath.<String>read(originalSession(eventId, original), "$.outcome")).isEqualTo("DUPLICATE");
    }

    @Test
    void addsTheOriginalToAPhotoAlreadyInTheGallery() throws Exception {
        String eventId = eventId(createEvent("Attach", "LIVE"));
        byte[] original = jpeg(1600, 1067);
        // First the compressed version only (e.g. during the event)...
        upload(adminToken(), eventId, "IMG_9000.JPG", original, jpeg(1024, 683), jpeg(640, 427)).andExpect(status().isOk());
        assertThat(JsonPath.<Boolean>read(originalAvailable(eventId), "$.photos[0].originalAvailable")).isFalse();
        // ...then the original for delivery: no second photo, the same one gains its original.
        String session = originalSession(eventId, original);
        assertThat(JsonPath.<Boolean>read(session, "$.needsVersions")).isFalse();
        String photoId = JsonPath.read(session, "$.photoId");
        putToStorage(JsonPath.read(session, "$.upload.url"), original);
        mvc.perform(post("/api/admin/events/" + eventId + "/photos/" + photoId + "/original")
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isNoContent());
        String list = originalAvailable(eventId);
        assertThat(JsonPath.<List<Object>>read(list, "$.photos")).hasSize(1);
        assertThat(JsonPath.<Boolean>read(list, "$.photos[0].originalAvailable")).isTrue();
    }
}
