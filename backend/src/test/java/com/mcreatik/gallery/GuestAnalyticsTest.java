package com.mcreatik.gallery;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.mcreatik.gallery.support.IntegrationTest;

/** Guests' anonymous activity becomes the studio's event analytics. */
class GuestAnalyticsTest extends IntegrationTest {

    private void report(String slug, String visitor, String items) throws Exception {
        mvc.perform(post("/api/public/events/" + slug + "/activity").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visitorId\":\"" + visitor + "\",\"items\":[" + items + "]}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void countsPeopleScansViewsDownloadsAndInstagram() throws Exception {
        String event = createEvent("Analytics", "LIVE");
        String eventId = eventId(event);
        String slug = slug(event);
        String token = createUploader(eventId, "Camera 1");
        String photo = uploadPhoto(token, "IMG_1.JPG", jpeg(800, 600));
        processAll();

        report(slug, "visitor-aaaa-1111", "{\"type\":\"VISIT\",\"source\":\"qr\"},"
                + "{\"type\":\"PHOTO_VIEW\",\"photoId\":\"" + photo + "\"},"
                + "{\"type\":\"DOWNLOAD\",\"photoId\":\"" + photo + "\"},"
                + "{\"type\":\"INSTAGRAM\"},{\"type\":\"NONSENSE\"}");
        report(slug, "visitor-bbbb-2222", "{\"type\":\"VISIT\",\"source\":\"link\"},"
                + "{\"type\":\"PHOTO_VIEW\",\"photoId\":\"" + photo + "\"}");

        mvc.perform(get("/api/admin/events/" + eventId + "/analytics").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.people").value(2))
                .andExpect(jsonPath("$.totals.qrScans").value(1))
                .andExpect(jsonPath("$.totals.visits").value(2))
                .andExpect(jsonPath("$.totals.photoViews").value(2))
                .andExpect(jsonPath("$.totals.downloads").value(1))
                .andExpect(jsonPath("$.totals.downloaders").value(1))
                .andExpect(jsonPath("$.totals.instagramClicks").value(1))
                .andExpect(jsonPath("$.hours.length()").value(1))
                .andExpect(jsonPath("$.hours[0].people").value(2))
                .andExpect(jsonPath("$.topPhotos[0].photoId").value(photo))
                .andExpect(jsonPath("$.topPhotos[0].views").value(2))
                .andExpect(jsonPath("$.topPhotos[0].downloads").value(1))
                .andExpect(jsonPath("$.lastActivityAt").exists());
    }

    @Test
    void rejectsMalformedVisitorsAndHiddenGalleries() throws Exception {
        String slug = slug(createEvent("Draft", "DRAFT"));
        mvc.perform(post("/api/public/events/" + slug + "/activity").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visitorId\":\"x\",\"items\":[{\"type\":\"VISIT\"}]}"))
                .andExpect(status().isNotFound());
        String live = slug(createEvent("Live", "LIVE"));
        mvc.perform(post("/api/public/events/" + live + "/activity").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visitorId\":\"bad id!\",\"items\":[{\"type\":\"VISIT\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyTheOwnerSeesTheAnalytics() throws Exception {
        String eventId = eventId(createEvent("Private", "LIVE"));
        mvc.perform(get("/api/admin/events/" + eventId + "/analytics")).andExpect(status().isUnauthorized());
    }
}
