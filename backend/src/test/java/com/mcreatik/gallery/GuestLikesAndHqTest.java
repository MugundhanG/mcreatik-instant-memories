package com.mcreatik.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.mcreatik.gallery.support.IntegrationTest;

/** Guest likes, the ranked photo lists, HD-photo requests and the guest gallery's categories. */
class GuestLikesAndHqTest extends IntegrationTest {

    private void report(String slug, String visitor, String items) throws Exception {
        mvc.perform(post("/api/public/events/" + slug + "/activity").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visitorId\":\"" + visitor + "\",\"items\":[" + items + "]}"))
                .andExpect(status().isNoContent());
    }

    private static String like(String type, String photo) {
        return "{\"type\":\"" + type + "\",\"photoId\":\"" + photo + "\"}";
    }

    private ResultActions hq(String slug, String photo, String contact) throws Exception {
        return mvc.perform(post("/api/public/events/" + slug + "/hq-requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"photoId\":\"" + photo + "\",\"name\":\"Priya\",\"contact\":\"" + contact + "\",\"visitorId\":\"visitor-hq-0001\"}"));
    }

    private String json(org.springframework.test.web.servlet.RequestBuilder r) throws Exception {
        return mvc.perform(r).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void likesCountOncePerGuestAndRankPhotos() throws Exception {
        String event = createEvent("Likes", "LIVE");
        String eventId = eventId(event);
        String slug = slug(event);
        String token = createUploader(eventId, "Camera 1");
        String a = uploadPhoto(token, "IMG_1.JPG", jpeg(800, 600));
        String b = uploadPhoto(token, "IMG_2.JPG", jpeg(800, 601));
        processAll();

        report(slug, "visitor-like-0001", like("LIKE", a) + "," + like("LIKE", a) + "," + like("LIKE", b));
        report(slug, "visitor-like-0002", like("LIKE", a) + "," + like("PHOTO_VIEW", b) + "," + like("PHOTO_VIEW", b)
                + "," + like("DOWNLOAD", b));
        report(slug, "visitor-like-0001", like("UNLIKE", b));

        mvc.perform(get("/api/admin/events/" + eventId + "/analytics").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.likes").value(2))
                .andExpect(jsonPath("$.mostLiked.length()").value(1))
                .andExpect(jsonPath("$.mostLiked[0].photoId").value(a))
                .andExpect(jsonPath("$.mostLiked[0].likes").value(2))
                .andExpect(jsonPath("$.mostDownloaded.length()").value(1))
                .andExpect(jsonPath("$.mostDownloaded[0].photoId").value(b))
                .andExpect(jsonPath("$.mostViewed[0].photoId").value(b))
                .andExpect(jsonPath("$.mostViewed[0].views").value(2));
    }

    @Test
    void guestsRequestHdPhotosAndTheStudioWorksTheList() throws Exception {
        String event = createEvent("HD", "LIVE");
        String eventId = eventId(event);
        String slug = slug(event);
        String photo = uploadPhoto(createUploader(eventId, "Camera 1"), "IMG_1.JPG", jpeg(800, 600));
        processAll();

        hq(slug, photo, "98765 43210").andExpect(status().isNoContent());
        hq(slug, photo, "9876543210").andExpect(status().isNoContent()); // same contact and photo: kept once
        hq(slug, photo, " Priya@Mail.COM ").andExpect(status().isNoContent());
        hq(slug, photo, "not a contact").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CONTACT"));
        hq(slug, java.util.UUID.randomUUID().toString(), "priya@mail.com").andExpect(status().isNotFound());

        String list = json(get("/api/admin/events/" + eventId + "/hq-requests").header("Authorization", bearer(adminToken())));
        assertThat(JsonPath.<List<String>>read(list, "$[*].contact")).containsExactlyInAnyOrder("+919876543210", "priya@mail.com");
        assertThat(JsonPath.<List<String>>read(list, "$[?(@.contact == 'priya@mail.com')].kind")).containsExactly("EMAIL");
        String id = JsonPath.<List<String>>read(list, "$[?(@.kind == 'WHATSAPP')].id").getFirst();

        mvc.perform(patch("/api/admin/events/" + eventId + "/hq-requests/" + id).header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"done\":true}")).andExpect(status().isNoContent());
        list = json(get("/api/admin/events/" + eventId + "/hq-requests").header("Authorization", bearer(adminToken())));
        assertThat(JsonPath.<List<Boolean>>read(list, "$[?(@.id == '" + id + "')].done")).containsExactly(true);

        // Another studio sees nothing of it.
        String email = "hq-" + java.util.UUID.randomUUID().toString().substring(0, 6) + "@studio.test";
        mvc.perform(post("/api/admin/studios").header("Authorization", bearer(adminToken())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Other studio\",\"email\":\"" + email + "\",\"password\":\"temporary-123\",\"plan\":\"UNLIMITED\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/admin/events/" + eventId + "/hq-requests").header("Authorization", bearer(login(email, "temporary-123"))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/events/" + eventId + "/hq-requests")).andExpect(status().isUnauthorized());
    }

    @Test
    void guestGalleryShowsCategoriesWithPhotosAndFiltersByThem() throws Exception {
        String event = createEvent("Categories", "LIVE");
        String eventId = eventId(event);
        String slug = slug(event);
        String token = createUploader(eventId, "Camera 1");
        String a = uploadPhoto(token, "IMG_1.JPG", jpeg(800, 600));
        uploadPhoto(token, "IMG_2.JPG", jpeg(800, 601));
        processAll();
        String categories = json(get("/api/admin/events/" + eventId + "/categories").header("Authorization", bearer(adminToken())));
        String cake = JsonPath.<List<String>>read(categories, "$[?(@.name == 'Cake cutting')].id").getFirst();
        mvc.perform(post("/api/admin/events/" + eventId + "/photos/category").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"photoIds\":[\"" + a + "\"],\"categoryId\":\"" + cake + "\"}"))
                .andExpect(status().is2xxSuccessful());

        mvc.perform(get("/api/public/events/" + slug + "/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Cake cutting"))
                .andExpect(jsonPath("$[0].photos").value(1));
        mvc.perform(get("/api/public/events/" + slug + "/photos?category=" + cake))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photos.length()").value(1))
                .andExpect(jsonPath("$.photos[0].id").value(a))
                .andExpect(jsonPath("$.photos[0].categoryId").value(cake));
        mvc.perform(get("/api/public/events/" + slug + "/photos")).andExpect(jsonPath("$.photos.length()").value(2));
    }
}
