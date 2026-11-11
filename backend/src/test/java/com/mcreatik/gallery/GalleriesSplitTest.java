package com.mcreatik.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.mcreatik.gallery.common.Tokens;
import com.mcreatik.gallery.support.IntegrationTest;

/** Separate Guest and Client galleries: who sees what, quality rules, categories and the off state. */
class GalleriesSplitTest extends IntegrationTest {

    private static final AtomicInteger IPS = new AtomicInteger();

    private ResultActions dashboardUpload(String eventId, String gallery, byte[] original, boolean withOriginal)
            throws Exception {
        var req = multipart("/api/admin/events/" + eventId + "/photos")
                .file(new MockMultipartFile("web", "web.jpg", "image/jpeg", jpeg(1024, 683)))
                .file(new MockMultipartFile("thumbnail", "thumb.jpg", "image/jpeg", jpeg(640, 427)))
                .param("fileName", "IMG.JPG").param("fileSize", String.valueOf(original.length))
                .param("mimeType", "image/jpeg").param("checksumSha256", Tokens.sha256Hex(original))
                .param("width", "6000").param("height", "4000")
                .param("original", String.valueOf(withOriginal)).header("Authorization", bearer(adminToken()));
        if (gallery != null) {
            req.param("gallery", gallery);
        }
        return mvc.perform(req);
    }

    /** The two steps of a full-quality upload: PUT the original to storage, then send the gallery versions. */
    private ResultActions clientUpload(String eventId, byte[] original) throws Exception {
        String session = mvc.perform(post("/api/admin/events/" + eventId + "/original-uploads")
                        .header("Authorization", bearer(adminToken())).contentType(MediaType.APPLICATION_JSON)
                        .content(uploadRequestJson("IMG.JPG", original).replace("}", ",\"gallery\":\"CLIENT\"}")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        putToStorage(JsonPath.read(session, "$.upload.url"), original);
        return dashboardUpload(eventId, "CLIENT", original, true);
    }

    private String list(String eventId, String gallery) throws Exception {
        return mvc.perform(get("/api/admin/events/" + eventId + "/photos?gallery=" + gallery)
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private int count(String json) {
        return JsonPath.<List<Object>>read(json, "$.photos").size();
    }

    private String coupleLogin(String eventId) throws Exception {
        String email = "split-" + UUID.randomUUID().toString().substring(0, 6) + "@client.test";
        mvc.perform(post("/api/admin/events/" + eventId + "/clients").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Couple\",\"email\":\"" + email + "\",\"password\":\"temporary-123\"}"))
                .andExpect(status().isCreated());
        int n = IPS.incrementAndGet();
        return JsonPath.read(mvc.perform(post("/api/client/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"temporary-123\"}")
                        .with(r -> {
                            r.setRemoteAddr("10.97.0." + n);
                            return r;
                        }))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
    }

    private String couplePhotos(String token) throws Exception {
        return mvc.perform(get("/api/client/photos").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void galleries(String eventId, boolean guests, boolean client) {
        jdbc.update("UPDATE events SET live_gallery_enabled = ?, client_gallery_enabled = ? WHERE id = ?", guests, client,
                UUID.fromString(eventId));
    }

    @Test
    void uploaderPhotosGoToTheGuestGalleryEvenWhileItIsOff() throws Exception {
        String event = createEvent("Cameras", "LIVE");
        String eventId = eventId(event);
        String token = createUploader(eventId, "Camera 1");
        galleries(eventId, false, true);
        uploadPhoto(token, "IMG_1.JPG", jpeg(800, 600));
        processAll();
        // Saved: the studio sees it in the guest tab, and it is not in the client tab.
        assertThat(count(list(eventId, "GUEST"))).isEqualTo(1);
        assertThat(JsonPath.<String>read(list(eventId, "GUEST"), "$.photos[0].gallery")).isEqualTo("GUEST");
        assertThat(count(list(eventId, "CLIENT"))).isZero();
        // Guests can't see it while the gallery is off, and can once it is on.
        mvc.perform(get("/api/public/events/" + slug(event) + "/photos")).andExpect(status().isForbidden());
        galleries(eventId, true, true);
        assertThat(count(publicPhotos(slug(event)))).isEqualTo(1);
    }

    @Test
    void clientUploadsAreFullQualityAndStayHiddenFromTheCoupleWhileTheirGalleryIsOff() throws Exception {
        String eventId = eventId(createEvent("Client off", "LIVE"));
        String couple = coupleLogin(eventId);
        galleries(eventId, true, false);
        byte[] original = jpeg(1600, 1067);
        clientUpload(eventId, original).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("UPLOAD"));
        String clientTab = list(eventId, "CLIENT");
        assertThat(count(clientTab)).isEqualTo(1);
        assertThat(JsonPath.<Boolean>read(clientTab, "$.photos[0].originalAvailable")).isTrue();
        assertThat(JsonPath.<String>read(clientTab, "$.photos[0].gallery")).isEqualTo("CLIENT");
        // Off: the couple is refused. On: they see the photo.
        mvc.perform(get("/api/client/photos").header("Authorization", bearer(couple))).andExpect(status().isForbidden());
        galleries(eventId, true, true);
        assertThat(count(couplePhotos(couple))).isEqualTo(1);
        // Guests never see it.
        assertThat(count(publicPhotos(slug(createEventSlug(eventId))))).isZero();
    }

    private String createEventSlug(String eventId) {
        return "{\"slug\":\"" + jdbc.queryForObject("SELECT slug FROM events WHERE id = ?", String.class,
                UUID.fromString(eventId)) + "\"}";
    }

    @Test
    void aGuestOriginalIsRefusedAndAClientPhotoNeedsItsOriginal() throws Exception {
        String eventId = eventId(createEvent("Rules", "LIVE"));
        dashboardUpload(eventId, "GUEST", jpeg(900, 600), true)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GUEST_COMPRESSED_ONLY"));
        dashboardUpload(eventId, "CLIENT", jpeg(900, 600), false)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CLIENT_ORIGINAL_REQUIRED"));
        byte[] original = jpeg(900, 600);
        mvc.perform(post("/api/admin/events/" + eventId + "/original-uploads")
                        .header("Authorization", bearer(adminToken())).contentType(MediaType.APPLICATION_JSON)
                        .content(uploadRequestJson("IMG.JPG", original).replace("}", ",\"gallery\":\"GUEST\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GUEST_COMPRESSED_ONLY"));
        // Compressed photos to the guest gallery work, and omitting the gallery means GUEST.
        dashboardUpload(eventId, null, jpeg(900, 600), false).andExpect(status().isOk());
        dashboardUpload(eventId, "GUEST", jpeg(900, 601), false).andExpect(status().isOk());
        assertThat(count(list(eventId, "GUEST"))).isEqualTo(2);
    }

    @Test
    void guestsNeverSeeClientPhotosAndTheCoupleNeverSeesGuestPhotosOnASplitEvent() throws Exception {
        String event = createEvent("Split", "LIVE");
        String eventId = eventId(event);
        String couple = coupleLogin(eventId);
        uploadPhoto(createUploader(eventId, "Camera 1"), "IMG_1.JPG", jpeg(800, 600));
        processAll();
        clientUpload(eventId, jpeg(1600, 1067)).andExpect(status().isOk());

        assertThat(count(publicPhotos(slug(event)))).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(mvc.perform(get("/api/public/events/" + slug(event)))
                .andReturn().getResponse().getContentAsString(), "$.photoCount")).isEqualTo(1);
        String mine = couplePhotos(couple);
        assertThat(count(mine)).isEqualTo(1);
        assertThat(JsonPath.<Boolean>read(mine, "$.photos[0].originalAvailable")).isTrue();
        mvc.perform(get("/api/client/me").header("Authorization", bearer(couple)))
                .andExpect(jsonPath("$.totalPhotos").value(1));
        // The couple cannot select or download a guest photo by id.
        String guestPhoto = JsonPath.read(list(eventId, "GUEST"), "$.photos[0].id");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/client/photos/" + guestPhoto + "/selected").header("Authorization", bearer(couple))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"selected\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/client/photos/" + guestPhoto + "/download").header("Authorization", bearer(couple)))
                .andExpect(status().isNotFound());
    }

    @Test
    void anOlderEventStillShowsTheCoupleEveryPhoto() throws Exception {
        String eventId = eventId(createEvent("Legacy", "LIVE"));
        jdbc.update("UPDATE events SET split_galleries = false WHERE id = ?", UUID.fromString(eventId));
        String couple = coupleLogin(eventId);
        uploadPhoto(createUploader(eventId, "Camera 1"), "IMG_1.JPG", jpeg(800, 600));
        processAll();
        clientUpload(eventId, jpeg(1600, 1067)).andExpect(status().isOk());
        assertThat(count(couplePhotos(couple))).isEqualTo(2);
    }

    @Test
    void categoriesBelongToAGalleryAndPhotosCannotCrossOver() throws Exception {
        String eventId = eventId(createEvent("Categories", "LIVE"));
        for (String gallery : List.of("GUEST", "CLIENT")) {
            String cats = mvc.perform(get("/api/admin/events/" + eventId + "/categories?gallery=" + gallery)
                            .header("Authorization", bearer(adminToken())))
                    .andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<String>>read(cats, "$[*].name")).hasSize(6);
            assertThat(JsonPath.<List<String>>read(cats, "$[*].gallery")).containsOnly(gallery);
        }
        String guestCat = JsonPath.<List<String>>read(mvc.perform(get("/api/admin/events/" + eventId + "/categories?gallery=GUEST")
                .header("Authorization", bearer(adminToken()))).andReturn().getResponse().getContentAsString(), "$[*].id").getFirst();
        String clientCat = JsonPath.<List<String>>read(mvc.perform(get("/api/admin/events/" + eventId + "/categories?gallery=CLIENT")
                .header("Authorization", bearer(adminToken()))).andReturn().getResponse().getContentAsString(), "$[*].id").getFirst();
        // A new category lands in the gallery it was created for.
        mvc.perform(post("/api/admin/events/" + eventId + "/categories").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Haldi\",\"gallery\":\"CLIENT\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.gallery").value("CLIENT"));

        uploadPhoto(createUploader(eventId, "Camera 1"), "IMG_1.JPG", jpeg(800, 600));
        processAll();
        String guestPhoto = JsonPath.read(list(eventId, "GUEST"), "$.photos[0].id");
        mvc.perform(post("/api/admin/events/" + eventId + "/photos/category").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photoIds\":[\"" + guestPhoto + "\"],\"categoryId\":\"" + clientCat + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GALLERY_MISMATCH"));
        mvc.perform(post("/api/admin/events/" + eventId + "/photos/category").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photoIds\":[\"" + guestPhoto + "\"],\"categoryId\":\"" + guestCat + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.moved").value(1));
        // Uploading into a category of the other gallery is refused too.
        dashboardUploadInto(eventId, "GUEST", clientCat).andExpect(status().isBadRequest());
    }

    private ResultActions dashboardUploadInto(String eventId, String gallery, String categoryId) throws Exception {
        byte[] original = jpeg(900, 600);
        return mvc.perform(multipart("/api/admin/events/" + eventId + "/photos")
                .file(new MockMultipartFile("web", "web.jpg", "image/jpeg", jpeg(1024, 683)))
                .file(new MockMultipartFile("thumbnail", "thumb.jpg", "image/jpeg", jpeg(640, 427)))
                .param("fileName", "IMG.JPG").param("fileSize", String.valueOf(original.length))
                .param("mimeType", "image/jpeg").param("checksumSha256", Tokens.sha256Hex(original))
                .param("width", "6000").param("height", "4000").param("gallery", gallery)
                .param("categoryId", categoryId).header("Authorization", bearer(adminToken())));
    }

    @Test
    void theSameFileCanSitInBothGalleries() throws Exception {
        String eventId = eventId(createEvent("Both", "LIVE"));
        byte[] file = jpeg(1600, 1067);
        dashboardUpload(eventId, "GUEST", file, false).andExpect(jsonPath("$.outcome").value("UPLOAD"));
        clientUpload(eventId, file).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("UPLOAD"));
        assertThat(count(list(eventId, "GUEST"))).isEqualTo(1);
        assertThat(count(list(eventId, "CLIENT"))).isEqualTo(1);
        // Within one gallery it is still a duplicate.
        dashboardUpload(eventId, "GUEST", file, false).andExpect(jsonPath("$.outcome").value("DUPLICATE"));
    }
}
