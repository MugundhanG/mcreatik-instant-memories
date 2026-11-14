package com.mcreatik.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.mcreatik.gallery.event.EventRepository;
import com.mcreatik.gallery.event.EventService;
import com.mcreatik.gallery.face.FakeFaceSearchProvider;
import com.mcreatik.gallery.face.FaceIndexWorker;
import com.mcreatik.gallery.face.FaceSearchProvider;
import com.mcreatik.gallery.face.FaceSearchService;
import com.mcreatik.gallery.support.IntegrationTest;

/** "Find my photos" with the in-memory fake engine (no AWS calls). */
@TestPropertySource(properties = {"gallery.face-search.enabled=true", "gallery.face-search.worker-enabled=false",
        "gallery.face-search.ip-per-hour=4", "gallery.face-search.event-daily-cap=6", "gallery.face-search.collection-prefix=mck-test"})
class FaceSearchTest extends IntegrationTest {

    private static final AtomicInteger IPS = new AtomicInteger();

    @Autowired
    FaceSearchProvider provider;
    @Autowired
    FaceIndexWorker faceWorker;
    @Autowired
    FaceSearchService faceService;
    @Autowired
    EventService eventService;
    @Autowired
    EventRepository eventRepository;

    FakeFaceSearchProvider fake;

    @BeforeEach
    void freshFake() {
        fake = (FakeFaceSearchProvider) provider;
        fake.reset();
    }

    private static String ip() {
        return "10.88." + (IPS.incrementAndGet() / 250) + "." + (IPS.get() % 250);
    }

    private String collection(String eventId) {
        return "mck-test-event-" + eventId;
    }

    private void setFaceSearch(String eventId, boolean on) throws Exception {
        mvc.perform(put("/api/admin/events/" + eventId + "/face-search").header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":" + on + "}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(on));
    }

    private void indexAll() {
        while (faceWorker.processNext()) {
            // drain
        }
    }

    /** A READY guest photo. */
    private String photo(String eventId, String uploader, String... people) throws Exception {
        String id = uploadPhoto(uploader, "p" + UUID.randomUUID() + ".jpg", jpeg(300, 200));
        processAll();
        if (people.length > 0) {
            fake.photoShows(UUID.fromString(id), people);
        }
        return id;
    }

    private ResultActions findMe(String slug, byte[] selfie, String remote) throws Exception {
        return mvc.perform(multipart("/api/public/events/" + slug + "/find-me")
                .file(new MockMultipartFile("selfie", "selfie.jpg", "image/jpeg", selfie))
                .with(r -> {
                    r.setRemoteAddr(remote);
                    return r;
                }));
    }

    private byte[] selfieOf(String person) throws Exception {
        byte[] bytes = jpeg(120, 120);
        fake.selfieShows(bytes, person);
        return bytes;
    }

    @Test
    void indexingBackfillsExistingPhotosAndReportsProgress() throws Exception {
        String event = createEvent("Backfill", "LIVE");
        String id = eventId(event);
        String up = createUploader(id, "cam");
        photo(id, up);
        photo(id, up);
        photo(id, up);
        mvc.perform(get("/api/admin/events/" + id + "/face-search").header("Authorization", bearer(adminToken())))
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.indexed").value(0)).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.mainPrice").value(999)).andExpect(jsonPath("$.quickPrice").value(499));
        mvc.perform(get("/api/public/events/" + slug(event))).andExpect(jsonPath("$.faceSearch").value(false));
        // Off: the worker does nothing.
        assertThat(faceWorker.processNext()).isFalse();

        setFaceSearch(id, true);
        mvc.perform(get("/api/public/events/" + slug(event))).andExpect(jsonPath("$.faceSearch").value(true));
        indexAll();
        mvc.perform(get("/api/admin/events/" + id + "/face-search").header("Authorization", bearer(adminToken())))
                .andExpect(jsonPath("$.indexed").value(3)).andExpect(jsonPath("$.total").value(3));
        assertThat(fake.hasCollection(collection(id))).isTrue();
        assertThat(fake.faceCount(collection(id))).isEqualTo(3);
        // A photo that arrives later is indexed too.
        photo(id, up);
        indexAll();
        assertThat(fake.faceCount(collection(id))).isEqualTo(4);
    }

    @Test
    void searchReturnsOnlyVisibleGuestPhotosOfThisEvent() throws Exception {
        String event = createEvent("Search", "LIVE");
        String id = eventId(event);
        String up = createUploader(id, "cam");
        setFaceSearch(id, true);
        String a = photo(id, up, "alice");
        String b = photo(id, up, "alice", "bob");
        String hidden = photo(id, up, "alice");
        String clientPhoto = photo(id, up, "alice");
        String notReady = photo(id, up, "alice");
        photo(id, up, "bob");
        // Another event with the same person must never show up.
        String other = createEvent("Other", "LIVE");
        String otherUp = createUploader(eventId(other), "cam2");
        setFaceSearch(eventId(other), true);
        photo(eventId(other), otherUp, "alice");
        indexAll();

        mvc.perform(patch("/api/admin/photos/" + hidden).header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":true}")).andExpect(status().isOk());
        jdbc.update("UPDATE photos SET gallery = 'CLIENT' WHERE id = ?", UUID.fromString(clientPhoto));
        jdbc.update("UPDATE photos SET status = 'PROCESSING' WHERE id = ?", UUID.fromString(notReady));
        assertThat(fake.deletedFaceIds).isEmpty(); // hiding is a query-time filter, no engine call

        String body = findMe(slug(event), selfieOf("alice"), ip()).andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(body, "$.photos[*].id")).containsExactly(b, a); // newest first
        assertThat(JsonPath.<String>read(body, "$.photos[0].thumbnailUrl")).isNotBlank();
        assertThat(JsonPath.<String>read(body, "$.photos[0].webUrl")).isNotBlank();
        assertThat(JsonPath.<String>read(body, "$.photos[0].cursor")).isNotBlank();
        assertThat(fake.searchedCollections).containsOnly(collection(id));
        // Someone not in any photo: an empty result, not an error.
        findMe(slug(event), selfieOf("nobody"), ip()).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void searchErrors() throws Exception {
        String event = createEvent("Errors", "LIVE");
        String id = eventId(event);
        // Not bought / not enabled: 404 FACE_SEARCH_OFF.
        findMe(slug(event), jpeg(100, 100), ip()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FACE_SEARCH_OFF"));
        setFaceSearch(id, true);
        byte[] none = jpeg(100, 100);
        fake.selfieHasNoFace(none);
        findMe(slug(event), none, ip()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NO_FACE"));
        mvc.perform(multipart("/api/public/events/" + slug(event) + "/find-me")
                .file(new MockMultipartFile("selfie", "x.txt", "text/plain", "hello".getBytes())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_IMAGE"));
        mvc.perform(multipart("/api/public/events/" + slug(event) + "/find-me")
                .file(new MockMultipartFile("selfie", "x.jpg", "image/jpeg", "not really a jpeg".getBytes())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_IMAGE"));
        mvc.perform(multipart("/api/public/events/" + slug(event) + "/find-me"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_IMAGE"));
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        big[0] = (byte) 0xFF;
        big[1] = (byte) 0xD8;
        big[2] = (byte) 0xFF;
        findMe(slug(event), big, ip()).andExpect(status().isPayloadTooLarge());
        mvc.perform(get("/api/public/events/nope-nope-1")).andExpect(status().isNotFound());
    }

    @Test
    void perIpHourlyLimitAndPerEventDailyCapReturn429() throws Exception {
        String event = createEvent("Caps", "LIVE");
        setFaceSearch(eventId(event), true);
        String same = ip();
        for (int i = 0; i < 4; i++) {
            findMe(slug(event), jpeg(100, 100), same).andExpect(status().isOk());
        }
        findMe(slug(event), jpeg(100, 100), same).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("FACE_SEARCH_LIMIT"));
        // The event's daily cap is 6: two more (other visitors) then it is spent for everyone.
        findMe(slug(event), jpeg(100, 100), ip()).andExpect(status().isOk());
        findMe(slug(event), jpeg(100, 100), ip()).andExpect(status().isOk());
        findMe(slug(event), jpeg(100, 100), ip()).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("FACE_SEARCH_LIMIT"));
    }

    @Test
    void theSelfieIsNeverStored() throws Exception {
        String event = createEvent("Private", "LIVE");
        setFaceSearch(eventId(event), true);
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "mcreatik-test-storage");
        long before = count(root);
        findMe(slug(event), jpeg(200, 200), ip()).andExpect(status().isOk());
        assertThat(count(root)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM photos", Long.class)).isZero();
    }

    private static long count(Path root) throws Exception {
        if (!Files.exists(root)) {
            return 0;
        }
        try (var s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void deletingPhotosRemovesTheirFacesFromTheEngine() throws Exception {
        String event = createEvent("Delete", "LIVE");
        String id = eventId(event);
        String up = createUploader(id, "cam");
        setFaceSearch(id, true);
        String one = photo(id, up, "alice");
        String two = photo(id, up, "alice");
        String three = photo(id, up, "bob");
        indexAll();
        assertThat(fake.faceCount(collection(id))).isEqualTo(3);
        List<String> oneFaces = jdbc.queryForList("SELECT face_id FROM photo_faces WHERE photo_id = ?", String.class, UUID.fromString(one));

        mvc.perform(delete("/api/admin/photos/" + one).header("Authorization", bearer(adminToken()))).andExpect(status().isNoContent());
        assertThat(fake.deletedFaceIds).containsExactlyElementsOf(oneFaces);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM photo_faces WHERE photo_id = ?", Long.class, UUID.fromString(one))).isZero();

        mvc.perform(post("/api/admin/events/" + id + "/photos/bulk").header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"DELETE\",\"photoIds\":[\"" + two + "\",\"" + three + "\"]}")).andExpect(status().isOk());
        assertThat(fake.faceCount(collection(id))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM photo_faces", Long.class)).isZero();
    }

    @Test
    void switchingOffDeletesTheCollectionAndTurningOnAgainReindexes() throws Exception {
        String event = createEvent("Toggle", "LIVE");
        String id = eventId(event);
        String up = createUploader(id, "cam");
        setFaceSearch(id, true);
        photo(id, up);
        indexAll();
        assertThat(fake.hasCollection(collection(id))).isTrue();
        setFaceSearch(id, false);
        assertThat(fake.deletedCollections).contains(collection(id));
        assertThat(fake.hasCollection(collection(id))).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM photo_faces", Long.class)).isZero();
        mvc.perform(get("/api/admin/events/" + id + "/face-search").header("Authorization", bearer(adminToken())))
                .andExpect(jsonPath("$.indexed").value(0)).andExpect(jsonPath("$.total").value(1));
        setFaceSearch(id, true);
        indexAll();
        assertThat(fake.faceCount(collection(id))).isEqualTo(1);
    }

    @Test
    void retentionAndEventDeletionDeleteTheCollection() throws Exception {
        String expiring = createEvent("Expiring", "LIVE");
        String ex = eventId(expiring);
        setFaceSearch(ex, true);
        photo(ex, createUploader(ex, "cam"));
        indexAll();
        jdbc.update("UPDATE events SET retention_until = ? WHERE id = ?", java.sql.Date.valueOf(LocalDate.now().minusDays(1)), UUID.fromString(ex));
        var due = eventService.expiredEvents(LocalDate.now()).stream().filter(e -> e.getId().toString().equals(ex)).findFirst().orElseThrow();
        eventService.deleteExpired(due);
        assertThat(fake.deletedCollections).contains(collection(ex));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM photo_faces WHERE event_id = ?", Long.class, UUID.fromString(ex))).isZero();

        String removed = createEvent("Removed", "LIVE");
        String rm = eventId(removed);
        setFaceSearch(rm, true);
        photo(rm, createUploader(rm, "cam"));
        indexAll();
        mvc.perform(delete("/api/admin/events/" + rm).header("Authorization", bearer(adminToken()))).andExpect(status().isNoContent());
        assertThat(fake.deletedCollections).contains(collection(rm));
    }

    @Test
    void theNightlyCheckDeletesOrphanCollectionsOnly() throws Exception {
        String event = createEvent("Kept", "LIVE");
        String id = eventId(event);
        setFaceSearch(id, true);
        photo(id, createUploader(id, "cam"));
        indexAll();
        String orphan = collection(UUID.randomUUID().toString());
        fake.plantCollection(orphan);
        assertThat(faceService.sweepOrphanCollections()).isEqualTo(1);
        assertThat(fake.hasCollection(orphan)).isFalse();
        assertThat(fake.hasCollection(collection(id))).isTrue();
    }

    // ---- Billing ----

    private String studioToken(int plan) throws Exception {
        String email = "face-" + UUID.randomUUID().toString().substring(0, 8) + "@client.test";
        String studioId = JsonPath.read(mvc.perform(post("/api/admin/studios").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lens\",\"email\":\"" + email + "\",\"password\":\"temporary-123\",\"plan\":\"PER_EVENT\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(patch("/api/admin/billing/studios/" + studioId).header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"addCredits\":2,\"addQuickCredits\":2,\"addClientCredits\":1}")).andExpect(status().isOk());
        String ipAddr = ip();
        return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"temporary-123\"}").with(r -> {
                            r.setRemoteAddr(ipAddr);
                            return r;
                        })).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
    }

    private String studioEvent(String token, String type) throws Exception {
        return eventId(mvc.perform(post("/api/admin/events").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Wed\",\"eventDate\":\"2026-12-01\",\"eventType\":\"" + type + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions payFace(String token, String eventId, String utr) throws Exception {
        return mvc.perform(post("/api/admin/billing/payments").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"item\":\"FACE_SEARCH\",\"eventId\":\"" + eventId + "\",\"utr\":\"" + utr + "\"}"));
    }

    @Test
    void theAddOnIsPricedByEventTypeAndSwitchesFaceSearchOn() throws Exception {
        mvc.perform(put("/api/admin/billing/settings").header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"upiId\":\"mcreatik@okicici\",\"payeeName\":\"McreatiK\",\"perEventPrice\":2500,\"faceSearchMainPrice\":999,\"faceSearchQuickPrice\":499}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.faceSearchMainPrice").value(999))
                .andExpect(jsonPath("$.faceSearchQuickPrice").value(499));
        mvc.perform(get("/api/public/pricing")).andExpect(jsonPath("$.faceSearchMainPrice").value(999))
                .andExpect(jsonPath("$.packages.faceSearchMain").value(999)).andExpect(jsonPath("$.packages.faceSearchQuick").value(499));
        String studio = studioToken(0);
        mvc.perform(get("/api/admin/billing").header("Authorization", bearer(studio)))
                .andExpect(jsonPath("$.prices.faceSearchMain").value(999)).andExpect(jsonPath("$.prices.faceSearchQuick").value(499));
        String main = studioEvent(studio, "MAIN");
        String quick = studioEvent(studio, "QUICK");
        String client = studioEvent(studio, "CLIENT");

        quote(studio, main).andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(999)).andExpect(jsonPath("$.item").value("FACE_SEARCH"));
        quote(studio, quick).andExpect(jsonPath("$.amount").value(499));
        quote(studio, client).andExpect(status().isBadRequest());
        // A studio cannot switch it on for free.
        mvc.perform(put("/api/admin/events/" + main + "/face-search").header("Authorization", bearer(studio))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/events/" + main + "/face-search").header("Authorization", bearer(studio)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));

        String paid = JsonPath.read(payFace(studio, main, "FACE" + System.nanoTime()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.item").value("FACE_SEARCH")).andExpect(jsonPath("$.amount").value(999))
                .andExpect(jsonPath("$.eventName").value("Wed")).andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        // Applied at once, like every other purchase; McreatiK's approval is only the check.
        mvc.perform(get("/api/admin/events/" + main + "/face-search").header("Authorization", bearer(studio)))
                .andExpect(jsonPath("$.enabled").value(true));
        quote(studio, main).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_ENABLED"));
        mvc.perform(post("/api/admin/billing/payments/" + paid + "/approve").header("Authorization", bearer(adminToken())))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT face_search_enabled FROM events WHERE id = ?", Boolean.class, UUID.fromString(main))).isTrue();

        // A rejected payment takes it back.
        String q = JsonPath.read(payFace(studio, quick, "FACE" + System.nanoTime()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(499)).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/admin/billing/payments/" + q + "/reject").header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT face_search_enabled FROM events WHERE id = ?", Boolean.class, UUID.fromString(quick))).isFalse();
    }

    private ResultActions quote(String token, String eventId) throws Exception {
        return mvc.perform(post("/api/admin/billing/quote").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"item\":\"FACE_SEARCH\",\"eventId\":\"" + eventId + "\"}"));
    }
}
