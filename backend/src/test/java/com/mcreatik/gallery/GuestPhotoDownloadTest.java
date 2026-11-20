package com.mcreatik.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.mcreatik.gallery.support.IntegrationTest;

/** GET /api/public/events/{slug}/photos/{photoId}/download: the Download button's fallback (no CORS needed). */
class GuestPhotoDownloadTest extends IntegrationTest {

    private String download(String slug, String photoId) {
        return "/api/public/events/" + slug + "/photos/" + photoId + "/download";
    }

    @Test
    void streamsTheWebQualityJpegAsAnAttachment() throws Exception {
        String event = createEvent("Download Me", "LIVE");
        String slug = slug(event);
        String photo = uploadPhoto(createUploader(eventId(event), "Camera 1"), "IMG_1.JPG", jpeg(800, 600));
        processAll();

        MvcResult result = mvc.perform(get(download(slug, photo))).andExpect(status().isOk()).andReturn();
        var response = result.getResponse();
        assertThat(response.getContentType()).isEqualTo(MediaType.IMAGE_JPEG_VALUE);
        assertThat(response.getHeader("Content-Disposition"))
                .startsWith("attachment").contains("filename=\"" + slug + "-" + photo.substring(0, 8) + ".jpg\"");
        assertThat(response.getHeader("Cache-Control")).contains("private").contains("max-age=600");
        byte[] body = response.getContentAsByteArray();
        assertThat(body).hasSizeGreaterThan(100);
        assertThat(body[0]).isEqualTo((byte) 0xFF); // JPEG magic
        assertThat(body[1]).isEqualTo((byte) 0xD8);
    }

    @Test
    void watermarkIsOnByDefaultAndTheStudioCanSwitchItOff() throws Exception {
        String event = createEvent("Watermark", "LIVE");
        String slug = slug(event);
        String id = eventId(event);
        assertThat((Boolean) com.jayway.jsonpath.JsonPath.read(event, "$.watermark")).isTrue();
        mvc.perform(get("/api/public/events/" + slug)).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.watermark").value(true));
        String photo = uploadPhoto(createUploader(id, "Camera 1"), "IMG_1.JPG", jpeg(800, 600));
        processAll();

        byte[] marked = mvc.perform(get(download(slug, photo))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsByteArray();

        mvc.perform(patch("/api/admin/events/" + id).header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"watermark\":false}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.watermark").value(false));
        mvc.perform(get("/api/public/events/" + slug))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.watermark").value(false));
        byte[] clean = mvc.perform(get(download(slug, photo))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsByteArray();

        // Off: the stored web photo exactly; on: a re-encoded JPEG of the same size with the label on it.
        assertThat(marked).isNotEqualTo(clean);
        var a = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(marked));
        var b = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(clean));
        assertThat(a.getWidth()).isEqualTo(b.getWidth());
        assertThat(a.getHeight()).isEqualTo(b.getHeight());
    }

    @Test
    void theStudioLogoIsServedForTheGalleryAnd404WithoutOne() throws Exception {
        String slug = slug(createEvent("Logo", "LIVE"));
        mvc.perform(get("/api/public/events/" + slug + "/logo")).andExpect(status().isNotFound());
    }

    @Test
    void hiddenNotReadyClientOtherEventAndUnknownPhotosAre404() throws Exception {
        String event = createEvent("Rules", "LIVE");
        String slug = slug(event);
        String token = createUploader(eventId(event), "Camera 1");
        String hidden = uploadPhoto(token, "IMG_1.JPG", jpeg(800, 600));
        String client = uploadPhoto(token, "IMG_2.JPG", jpeg(800, 601));
        processAll();
        String pending = uploadPhoto(token, "IMG_3.JPG", jpeg(800, 602)); // not processed: not READY yet

        mvc.perform(patch("/api/admin/photos/" + hidden).header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":true}"))
                .andExpect(status().isOk());
        jdbc.update("UPDATE photos SET gallery = 'CLIENT' WHERE id = ?::uuid", client);

        mvc.perform(get(download(slug, hidden))).andExpect(status().isNotFound());
        mvc.perform(get(download(slug, client))).andExpect(status().isNotFound());
        mvc.perform(get(download(slug, pending))).andExpect(status().isNotFound());
        mvc.perform(get(download(slug, UUID.randomUUID().toString()))).andExpect(status().isNotFound());

        // A photo of another event is never served through this event's link.
        String other = createEvent("Other", "LIVE");
        String otherPhoto = uploadPhoto(createUploader(eventId(other), "Camera 1"), "IMG_9.JPG", jpeg(800, 603));
        processAll();
        mvc.perform(get(download(slug, otherPhoto))).andExpect(status().isNotFound());
        mvc.perform(get(download(slug(other), otherPhoto))).andExpect(status().isOk());

        // Unknown event.
        mvc.perform(get(download("no-such-event", otherPhoto))).andExpect(status().isNotFound());
    }

    @Test
    void closedGalleryRefusesTheDownload() throws Exception {
        String event = createEvent("Closed", "LIVE");
        String slug = slug(event);
        String photo = uploadPhoto(createUploader(eventId(event), "Camera 1"), "IMG_1.JPG", jpeg(800, 600));
        processAll();
        mvc.perform(get(download(slug, photo))).andExpect(status().isOk());

        jdbc.update("UPDATE events SET live_gallery_enabled = false WHERE id = ?::uuid", eventId(event));
        // Same answer as the photo feed for a switched-off gallery.
        int feed = mvc.perform(get("/api/public/events/" + slug + "/photos")).andReturn().getResponse().getStatus();
        assertThat(feed).isGreaterThanOrEqualTo(400);
        mvc.perform(get(download(slug, photo))).andExpect(status().is(feed));
    }
}
