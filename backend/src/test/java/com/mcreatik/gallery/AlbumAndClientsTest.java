package com.mcreatik.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.mcreatik.gallery.support.IntegrationTest;

/** Categories, the couple's login and their album selection. */
class AlbumAndClientsTest extends IntegrationTest {

    private static final AtomicInteger IPS = new AtomicInteger();

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder r) throws Exception {
        return r.header("Authorization", bearer(adminToken())).contentType(MediaType.APPLICATION_JSON);
    }

    private String json(MockHttpServletRequestBuilder r) throws Exception {
        return mvc.perform(r).andReturn().getResponse().getContentAsString();
    }

    private String clientLogin(String email, String password) throws Exception {
        int n = IPS.incrementAndGet();
        return JsonPath.read(mvc.perform(post("/api/client/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                        .with(r -> {
                            r.setRemoteAddr("10.99.0." + n);
                            return r;
                        }))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
    }

    record Setup(String eventId, List<String> photos, String cakeCategory) {
    }

    private Setup eventWithPhotos() throws Exception {
        String eventId = eventId(createEvent("Album", "LIVE"));
        // An event from before the galleries were split: the couple sees every photo, camera uploads included.
        jdbc.update("UPDATE events SET split_galleries = false WHERE id = ?", java.util.UUID.fromString(eventId));
        jdbc.update("DELETE FROM event_categories WHERE event_id = ? AND gallery = 'CLIENT'", java.util.UUID.fromString(eventId));
        String token = createUploader(eventId, "Camera 1");
        List<String> photos = List.of(uploadPhoto(token, "IMG_1.JPG", jpeg(800, 600)),
                uploadPhoto(token, "IMG_2.JPG", jpeg(800, 601)), uploadPhoto(token, "IMG_3.JPG", jpeg(800, 602)));
        processAll();
        String categories = json(admin(get("/api/admin/events/" + eventId + "/categories")));
        String cake = JsonPath.<List<String>>read(categories, "$[?(@.name == 'Cake cutting')].id").getFirst();
        return new Setup(eventId, photos, cake);
    }

    @Test
    void newEventsHaveTheDefaultCategoriesAndStudiosSortPhotos() throws Exception {
        Setup s = eventWithPhotos();
        assertThat(JsonPath.<List<String>>read(json(admin(get("/api/admin/events/" + s.eventId() + "/categories"))), "$[*].name"))
                .containsExactly("Couple", "Family & Groups", "Rituals", "Cake cutting", "Main event", "Candid");

        mvc.perform(admin(post("/api/admin/events/" + s.eventId() + "/photos/category"))
                        .content("{\"photoIds\":[\"" + s.photos().get(0) + "\",\"" + s.photos().get(1) + "\"],\"categoryId\":\"" + s.cakeCategory() + "\"}"))
                .andExpect(jsonPath("$.moved").value(2));
        String cake = json(admin(get("/api/admin/events/" + s.eventId() + "/photos?category=" + s.cakeCategory())));
        assertThat(JsonPath.<List<String>>read(cake, "$.photos[*].id")).containsExactlyInAnyOrder(s.photos().get(0), s.photos().get(1));
        String unsorted = json(admin(get("/api/admin/events/" + s.eventId() + "/photos?category=none")));
        assertThat(JsonPath.<List<String>>read(unsorted, "$.photos[*].id")).containsExactly(s.photos().get(2));

        // Custom categories: add, rename, delete (photos stay, just unsorted).
        String extra = JsonPath.read(json(admin(post("/api/admin/events/" + s.eventId() + "/categories")).content("{\"name\":\"Haldi\"}")), "$.id");
        mvc.perform(admin(patch("/api/admin/events/" + s.eventId() + "/categories/" + extra)).content("{\"name\":\"Haldi & Mehendi\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(admin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                "/api/admin/events/" + s.eventId() + "/categories/" + s.cakeCategory()))).andExpect(status().isNoContent());
        assertThat(JsonPath.<List<String>>read(json(admin(get("/api/admin/events/" + s.eventId() + "/photos?category=none"))), "$.photos")).hasSize(3);
    }

    @Test
    void theCoupleBrowsesByCategoryAndSelectsWithinTheLimit() throws Exception {
        Setup s = eventWithPhotos();
        mvc.perform(admin(post("/api/admin/events/" + s.eventId() + "/photos/category"))
                .content("{\"photoIds\":[\"" + s.photos().get(0) + "\"],\"categoryId\":\"" + s.cakeCategory() + "\"}"));
        String email = "couple-" + UUID.randomUUID().toString().substring(0, 6) + "@client.test";
        mvc.perform(admin(post("/api/admin/events/" + s.eventId() + "/clients"))
                        .content("{\"name\":\"Arun & Priya\",\"email\":\"" + email.toUpperCase() + "\",\"password\":\"temporary-123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email));
        mvc.perform(admin(put("/api/admin/events/" + s.eventId() + "/album")).content("{\"limit\":1}"))
                .andExpect(status().isNoContent());

        String client = clientLogin(email, "temporary-123");
        mvc.perform(get("/api/client/me").header("Authorization", bearer(client)))
                .andExpect(jsonPath("$.event.name").value("Album"))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.totalPhotos").value(3))
                .andExpect(jsonPath("$.uncategorizedPhotos").value(2))
                .andExpect(jsonPath("$.albumLimit").value(1))
                .andExpect(jsonPath("$.categories[?(@.name == 'Cake cutting')].photos").value(1));
        String cake = json(get("/api/client/photos?category=" + s.cakeCategory()).header("Authorization", bearer(client)));
        assertThat(JsonPath.<List<String>>read(cake, "$.photos[*].id")).containsExactly(s.photos().get(0));

        mvc.perform(put("/api/client/photos/" + s.photos().get(0) + "/selected").header("Authorization", bearer(client))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"selected\":true}"))
                .andExpect(jsonPath("$.selectedCount").value(1));
        mvc.perform(put("/api/client/photos/" + s.photos().get(1) + "/selected").header("Authorization", bearer(client))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"selected\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALBUM_LIMIT_REACHED"));
        String mine = json(get("/api/client/photos?selected=true").header("Authorization", bearer(client)));
        assertThat(JsonPath.<List<String>>read(mine, "$.photos[*].id")).containsExactly(s.photos().get(0));
        assertThat(JsonPath.<List<Boolean>>read(mine, "$.photos[*].selected")).containsExactly(true);

        // The studio sees the selection with file names (to build the album).
        mvc.perform(admin(get("/api/admin/events/" + s.eventId() + "/album")))
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.selected[0].fileName").value("IMG_1.JPG"))
                .andExpect(jsonPath("$.selected[0].selectedBy").value("Arun & Priya"));
        String selectedForStudio = json(admin(get("/api/admin/events/" + s.eventId() + "/photos?selected=true")));
        assertThat(JsonPath.<List<Boolean>>read(selectedForStudio, "$.photos[*].selected")).containsExactly(true);

        // These camera uploads kept their originals: the couple downloads full quality.
        mvc.perform(get("/api/client/photos/" + s.photos().get(0) + "/download").header("Authorization", bearer(client)))
                .andExpect(jsonPath("$.original").value(true))
                .andExpect(jsonPath("$.url").isNotEmpty());
    }

    @Test
    void clientsOnlyReachTheirOwnEvent() throws Exception {
        Setup mine = eventWithPhotos();
        Setup other = eventWithPhotos();
        String email = "only-" + UUID.randomUUID().toString().substring(0, 6) + "@client.test";
        String clientId = JsonPath.read(json(admin(post("/api/admin/events/" + mine.eventId() + "/clients"))
                .content("{\"name\":\"C\",\"email\":\"" + email + "\",\"password\":\"temporary-123\"}")), "$.id");
        String client = clientLogin(email, "temporary-123");

        mvc.perform(put("/api/client/photos/" + other.photos().get(0) + "/selected").header("Authorization", bearer(client))
                .contentType(MediaType.APPLICATION_JSON).content("{\"selected\":true}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/client/photos/" + other.photos().get(0) + "/download").header("Authorization", bearer(client)))
                .andExpect(status().isNotFound());
        // A client token is no studio login (refused as unknown user).
        mvc.perform(get("/api/admin/events").header("Authorization", bearer(client))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/client/me").header("Authorization", bearer(adminToken()))).andExpect(status().isForbidden());

        // Changing the password logs out other devices; a reset by the studio logs out every device.
        String otherDevice = clientLogin(email, "temporary-123");
        String fresh = JsonPath.read(json(post("/api/client/password").header("Authorization", bearer(client))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"temporary-123\",\"newPassword\":\"our-own-password\"}")), "$.token");
        mvc.perform(get("/api/client/me").header("Authorization", bearer(otherDevice))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/client/me").header("Authorization", bearer(fresh))).andExpect(status().isOk());
        mvc.perform(admin(patch("/api/admin/events/" + mine.eventId() + "/clients/" + clientId)).content("{\"newPassword\":\"studio-reset-123\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/client/me").header("Authorization", bearer(fresh))).andExpect(status().isUnauthorized());
        client = clientLogin(email, "studio-reset-123");

        // Turning the login off locks the couple out at once.
        mvc.perform(admin(patch("/api/admin/events/" + mine.eventId() + "/clients/" + clientId)).content("{\"disabled\":true}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/client/me").header("Authorization", bearer(client))).andExpect(status().isUnauthorized());
    }
}
