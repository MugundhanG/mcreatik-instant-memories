package com.mcreatik.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;
import com.mcreatik.gallery.support.IntegrationTest;

/** Dashboard photo tools: hide from guests, like, filter, sort and page without skipping after deletes. */
class AdminPhotoManagementTest extends IntegrationTest {

    private String event;
    private String camA;
    private String camB;
    private String a1;
    private String a2;
    private String b1;

    @BeforeEach
    void threePhotosFromTwoCameras() throws Exception {
        event = createEvent("Manage", "LIVE");
        String tokenA = createUploader(eventId(event), "Cam A");
        String tokenB = createUploader(eventId(event), "Cam B");
        a1 = uploadPhoto(tokenA, "A1.JPG", jpeg(800, 600));
        a2 = uploadPhoto(tokenA, "A2.JPG", jpeg(800, 600));
        b1 = uploadPhoto(tokenB, "B1.JPG", jpeg(800, 600));
        processAll();
        // Camera clock times (stored as UTC, as EXIF has no zone): A1 on day 1, A2 and B1 on day 2.
        jdbc.update("update photos set captured_at = '2026-10-24 18:00:00+00' where id = ?::uuid", a1);
        jdbc.update("update photos set captured_at = '2026-10-25 10:00:00+00' where id = ?::uuid", a2);
        jdbc.update("update photos set captured_at = '2026-10-25 11:00:00+00' where id = ?::uuid", b1);
        String uploaders = mvc.perform(get("/api/admin/events/" + eventId(event) + "/stats")
                .header("Authorization", bearer(adminToken()))).andReturn().getResponse().getContentAsString();
        camA = JsonPath.read(uploaders, "$.uploaders[0].id");
        camB = JsonPath.read(uploaders, "$.uploaders[1].id");
    }

    private List<String> ids(String query) throws Exception {
        String page = mvc.perform(get("/api/admin/events/" + eventId(event) + "/photos" + query)
                .header("Authorization", bearer(adminToken()))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(page, "$.photos[*].id");
    }

    private void patchPhoto(String id, String body) throws Exception {
        mvc.perform(patch("/api/admin/photos/" + id).header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    @Test
    void sortsByCaptureTimeOrCameraAndFiltersByCameraAndDay() throws Exception {
        assertThat(ids("")).containsExactly(b1, a2, a1);
        assertThat(ids("?sort=OLDEST")).containsExactly(a1, a2, b1);
        assertThat(ids("?sort=CAMERA")).containsExactly(a1, a2, b1);
        assertThat(ids("?uploaderId=" + camB)).containsExactly(b1);
        assertThat(ids("?uploaderId=" + camA + "&sort=OLDEST")).containsExactly(a1, a2);
        assertThat(ids("?day=2026-10-25")).containsExactly(b1, a2);

        mvc.perform(get("/api/admin/events/" + eventId(event) + "/photo-facets")
                        .header("Authorization", bearer(adminToken())))
                .andExpect(jsonPath("$.days[0].day").value("2026-10-24"))
                .andExpect(jsonPath("$.days[0].photos").value(1))
                .andExpect(jsonPath("$.days[1].day").value("2026-10-25"))
                .andExpect(jsonPath("$.days[1].photos").value(2));
    }

    @Test
    void likedPhotosCanBeFiltered() throws Exception {
        patchPhoto(a2, "{\"liked\":true}");
        assertThat(ids("?liked=true")).containsExactly(a2);
        mvc.perform(get("/api/admin/events/" + eventId(event) + "/photo-facets")
                .header("Authorization", bearer(adminToken()))).andExpect(jsonPath("$.liked").value(1));
        patchPhoto(a2, "{\"liked\":false}");
        assertThat(ids("?liked=true")).isEmpty();
    }

    @Test
    void hiddenPhotosLeaveTheGuestGalleryAndComeBack() throws Exception {
        mvc.perform(patch("/api/admin/events/" + eventId(event)).header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"coverPhotoId\":\"" + b1 + "\"}"))
                .andExpect(status().isOk());

        patchPhoto(b1, "{\"hidden\":true}");
        List<String> guest = JsonPath.read(publicPhotos(slug(event)), "$.photos[*].id");
        assertThat(guest).containsExactly(a2, a1);
        mvc.perform(get("/api/public/events/" + slug(event)))
                .andExpect(jsonPath("$.photoCount").value(2))
                .andExpect(jsonPath("$.coverUrl").doesNotExist());
        // Still in the dashboard, marked hidden.
        assertThat(ids("?hidden=true")).containsExactly(b1);
        mvc.perform(get("/api/admin/events/" + eventId(event) + "/stats").header("Authorization", bearer(adminToken())))
                .andExpect(jsonPath("$.hiddenPhotos").value(1));
        // A hidden photo cannot become the cover.
        mvc.perform(patch("/api/admin/events/" + eventId(event)).header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"coverPhotoId\":\"" + b1 + "\"}"))
                .andExpect(status().isBadRequest());

        patchPhoto(b1, "{\"hidden\":false}");
        guest = JsonPath.read(publicPhotos(slug(event)), "$.photos[*].id");
        assertThat(guest).containsExactly(b1, a2, a1);
    }

    @Test
    void pagesByOffsetSoDeletingNeverSkipsPhotos() throws Exception {
        String first = mvc.perform(get("/api/admin/events/" + eventId(event) + "/photos?limit=2")
                .header("Authorization", bearer(adminToken()))).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(first, "$.photos[*].id")).containsExactly(b1, a2);
        assertThat(JsonPath.<Integer>read(first, "$.nextOffset")).isEqualTo(2);

        // The dashboard deletes one it already shows, then asks for "what I have now" = 1.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/admin/photos/" + a2).header("Authorization", bearer(adminToken())))
                .andExpect(status().isNoContent());
        String next = mvc.perform(get("/api/admin/events/" + eventId(event) + "/photos?limit=2&offset=1")
                .header("Authorization", bearer(adminToken()))).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(next, "$.photos[*].id")).containsExactly(a1);
        assertThat((Object) JsonPath.read(next, "$.nextOffset")).isNull();
    }
}
