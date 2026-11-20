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
        "gallery.face-search.visitor-per-hour=4", "gallery.face-search.ip-per-hour=300", "gallery.face-search.event-daily-cap=6", "gallery.face-search.collection-prefix=mck-test"})
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

    private ResultActions findMeAs(String slug, byte[] selfie, String remote, String visitor) throws Exception {
        return mvc.perform(multipart("/api/public/events/" + slug + "/find-me")
                .file(new MockMultipartFile("selfie", "selfie.jpg", "image/jpeg", selfie)).param("visitorId", visitor)
                .with(r -> {
                    r.setRemoteAddr(remote);
                    return r;
                }));
    }

    @Test
    void theHourlyLimitIsPerVisitorSoAVenueSharingOneIpIsNotBlocked() throws Exception {
        String event = createEvent("Venue", "LIVE");
        setFaceSearch(eventId(event), true);
        String wifi = ip();
        findMeAs(slug(event), jpeg(100, 100), wifi, "visitor-aaaa1111").andExpect(status().isOk());
        findMeAs(slug(event), jpeg(100, 100), wifi, "visitor-bbbb2222").andExpect(status().isOk());
        for (int i = 0; i < 2; i++) {
            findMeAs(slug(event), jpeg(100, 100), wifi, "visitor-cccc3333").andExpect(status().isOk());
        }
        // Daily cap is 6, 4 used. The same person keeps their own 4-an-hour limit even on another IP.
        findMeAs(slug(event), jpeg(100, 100), wifi, "visitor-cccc3333").andExpect(status().isOk());
        findMeAs(slug(event), jpeg(100, 100), ip(), "visitor-cccc3333").andExpect(status().isOk());
        findMeAs(slug(event), jpeg(100, 100), ip(), "visitor-cccc3333").andExpect(status().isTooManyRequests());
    }

    @Test
    void searchesThatFindNoFaceDoNotBurnTheEventDailyCap() throws Exception {
        String event = createEvent("NoBurn", "LIVE");
        setFaceSearch(eventId(event), true);
        byte[] none = jpeg(100, 100);
        fake.selfieHasNoFace(none);
        for (int i = 0; i < 8; i++) { // more than the daily cap of 6
            findMe(slug(event), none, ip()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NO_FACE"));
        }
        for (int i = 0; i < 6; i++) {
            findMe(slug(event), jpeg(100, 100), ip()).andExpect(status().isOk());
        }
        findMe(slug(event), jpeg(100, 100), ip()).andExpect(status().isTooManyRequests());
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

    // ---- Remember me (v2) ----

    private String matchIdOf(ResultActions found) throws Exception {
        return JsonPath.read(found.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.matchId");
    }

    private ResultActions getMatch(String slug, String matchId) throws Exception {
        return mvc.perform(get("/api/public/events/" + slug + "/matches/" + matchId));
    }

    private ResultActions refresh(String slug, String matchId, String remote) throws Exception {
        return mvc.perform(post("/api/public/events/" + slug + "/matches/" + matchId + "/refresh").with(r -> {
            r.setRemoteAddr(remote);
            return r;
        }));
    }

    @Test
    void aSearchCreatesAMatchWithAtMostFiveSeeds() throws Exception {
        String event = createEvent("Remember", "LIVE");
        String id = eventId(event);
        String up = createUploader(id, "cam");
        setFaceSearch(id, true);
        for (int i = 0; i < 7; i++) {
            photo(id, up, "alice");
        }
        indexAll();
        String matchId = matchIdOf(findMe(slug(event), selfieOf("alice"), ip()).andExpect(jsonPath("$.count").value(7)));
        UUID mid = UUID.fromString(matchId);
        assertThat(jdbc.queryForObject("SELECT cardinality(face_ids) FROM guest_matches WHERE id = ?", Integer.class, mid)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT cardinality(seed_face_ids) FROM guest_matches WHERE id = ?", Integer.class, mid)).isEqualTo(5);
        // Nothing matched: no match is kept (there is nothing to remember).
        findMe(slug(event), selfieOf("nobody"), ip()).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.matchId").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guest_matches WHERE event_id = ?", Long.class, UUID.fromString(id))).isEqualTo(1);
    }

    @Test
    void gettingAMatchIsADatabaseLookupThatHidesHiddenAndDeletedPhotos() throws Exception {
        String event = createEvent("Lookup", "LIVE");
        String id = eventId(event);
        String up = createUploader(id, "cam");
        setFaceSearch(id, true);
        String a = photo(id, up, "alice");
        String b = photo(id, up, "alice");
        String c = photo(id, up, "alice");
        indexAll();
        String matchId = matchIdOf(findMe(slug(event), selfieOf("alice"), ip()));
        fake.searchedCollections.clear();

        String body = getMatch(slug(event), matchId).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.hasNew").value(false)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(body, "$.photos[*].id")).containsExactly(c, b, a);
        mvc.perform(patch("/api/admin/photos/" + b).header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":true}")).andExpect(status().isOk());
        mvc.perform(delete("/api/admin/photos/" + c).header("Authorization", bearer(adminToken()))).andExpect(status().isNoContent());
        body = getMatch(slug(event), matchId).andExpect(jsonPath("$.count").value(1)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(body, "$.photos[*].id")).containsExactly(a);
        assertThat(fake.searchedCollections).isEmpty();
        assertThat(fake.searchedByFaceId).isEmpty();

        // Unknown, malformed, and another event's match id are all just "gone".
        getMatch(slug(event), UUID.randomUUID().toString()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MATCH_NOT_FOUND"));
        getMatch(slug(event), "not-a-uuid").andExpect(status().isNotFound());
        String other = createEvent("Else", "LIVE");
        setFaceSearch(eventId(other), true);
        getMatch(slug(other), matchId).andExpect(status().isNotFound());
    }

    @Test
    void refreshAsksTheEngineOnlyWhenPhotosWereIndexedSinceTheLastCheck() throws Exception {
        String event = createEvent("Refresh", "LIVE");
        String id = eventId(event);
        String up = createUploader(id, "cam");
        setFaceSearch(id, true);
        photo(id, up, "alice");
        photo(id, up, "bob");
        indexAll();
        String matchId = matchIdOf(findMe(slug(event), selfieOf("alice"), ip()).andExpect(jsonPath("$.count").value(1)));
        String same = ip();

        // Nothing new: straight from the database.
        refresh(slug(event), matchId, same).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1)).andExpect(jsonPath("$.hasNew").value(false));
        assertThat(fake.searchedByFaceId).isEmpty();

        // A photo of someone else arrives: hasNew, a refresh searches once per seed, finds nothing new, and clears the hint.
        photo(id, up, "carol");
        indexAll();
        getMatch(slug(event), matchId).andExpect(jsonPath("$.hasNew").value(true)).andExpect(jsonPath("$.count").value(1));
        refresh(slug(event), matchId, same).andExpect(jsonPath("$.count").value(1)).andExpect(jsonPath("$.hasNew").value(false));
        assertThat(fake.searchedByFaceId).hasSize(1);

        // A new photo of alice: merged in.
        String fresh = photo(id, up, "alice");
        indexAll();
        String body = refresh(slug(event), matchId, same).andExpect(jsonPath("$.count").value(2)).andExpect(jsonPath("$.hasNew").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(body, "$.photos[*].id")).contains(fresh);
        assertThat(fake.searchedByFaceId).hasSize(2);
        refresh(slug(event), matchId, same).andExpect(jsonPath("$.count").value(2));
        assertThat(fake.searchedByFaceId).hasSize(2); // checked again, nothing new: no engine call
        getMatch(slug(event), matchId).andExpect(jsonPath("$.count").value(2));
        refresh(slug(event), UUID.randomUUID().toString(), same).andExpect(status().isNotFound());
    }

    @Test
    void refreshIsBoundByTheSameCapsAsSearch() throws Exception {
        String event = createEvent("RefreshCaps", "LIVE");
        String id = eventId(event);
        String up = createUploader(id, "cam");
        setFaceSearch(id, true);
        photo(id, up, "alice");
        indexAll();
        String same = ip();
        String matchId = matchIdOf(findMe(slug(event), selfieOf("alice"), same)); // 1 of this visitor's 4 searches an hour
        for (int i = 0; i < 3; i++) {
            photo(id, up, "alice");
            indexAll();
            refresh(slug(event), matchId, same).andExpect(status().isOk());
        }
        photo(id, up, "alice");
        indexAll();
        refresh(slug(event), matchId, same).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("FACE_SEARCH_LIMIT"));
        getMatch(slug(event), matchId).andExpect(status().isOk()).andExpect(jsonPath("$.hasNew").value(true)); // reading is free

        // The event's daily cap (6) is shared with other visitors: 4 used above, 2 left.
        String other = ip();
        findMe(slug(event), selfieOf("alice"), other).andExpect(status().isOk());
        refresh(slug(event), matchId, ip()).andExpect(status().isOk());
        photo(id, up, "alice");
        indexAll();
        refresh(slug(event), matchId, ip()).andExpect(status().isTooManyRequests());
    }

    @Test
    void forgetMeDeletesTheMatch() throws Exception {
        String event = createEvent("Forget", "LIVE");
        String id = eventId(event);
        setFaceSearch(id, true);
        photo(id, createUploader(id, "cam"), "alice");
        indexAll();
        String matchId = matchIdOf(findMe(slug(event), selfieOf("alice"), ip()));
        mvc.perform(delete("/api/public/events/" + slug(event) + "/matches/" + matchId)).andExpect(status().isNoContent());
        getMatch(slug(event), matchId).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guest_matches", Long.class)).isZero();
        mvc.perform(delete("/api/public/events/" + slug(event) + "/matches/" + matchId)).andExpect(status().isNoContent()); // repeat is fine
    }

    @Test
    void everyCleanupPathRemovesMatches() throws Exception {
        // Add-on switched off.
        String off = createEvent("MatchOff", "LIVE");
        String offId = eventId(off);
        setFaceSearch(offId, true);
        photo(offId, createUploader(offId, "cam"), "alice");
        indexAll();
        String offMatch = matchIdOf(findMe(slug(off), selfieOf("alice"), ip()));
        setFaceSearch(offId, false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guest_matches WHERE event_id = ?", Long.class, UUID.fromString(offId))).isZero();
        getMatch(slug(off), offMatch).andExpect(status().isNotFound());

        // Retention: the event row stays (archived), so the matches must be deleted explicitly.
        String expiring = createEvent("MatchExpiring", "LIVE");
        String ex = eventId(expiring);
        setFaceSearch(ex, true);
        photo(ex, createUploader(ex, "cam"), "alice");
        indexAll();
        findMe(slug(expiring), selfieOf("alice"), ip()).andExpect(status().isOk()).andExpect(jsonPath("$.matchId").exists());
        jdbc.update("UPDATE events SET retention_until = ? WHERE id = ?", java.sql.Date.valueOf(LocalDate.now().minusDays(1)), UUID.fromString(ex));
        var due = eventService.expiredEvents(LocalDate.now()).stream().filter(e -> e.getId().toString().equals(ex)).findFirst().orElseThrow();
        eventService.deleteExpired(due);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guest_matches WHERE event_id = ?", Long.class, UUID.fromString(ex))).isZero();

        // The event is removed.
        String removed = createEvent("MatchRemoved", "LIVE");
        String rm = eventId(removed);
        setFaceSearch(rm, true);
        photo(rm, createUploader(rm, "cam"), "alice");
        indexAll();
        findMe(slug(removed), selfieOf("alice"), ip()).andExpect(status().isOk()).andExpect(jsonPath("$.matchId").exists());
        mvc.perform(delete("/api/admin/events/" + rm).header("Authorization", bearer(adminToken()))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guest_matches WHERE event_id = ?", Long.class, UUID.fromString(rm))).isZero();

        // The nightly check: a match left behind on an event without the add-on.
        String stale = createEvent("MatchStale", "LIVE");
        UUID staleId = UUID.fromString(eventId(stale));
        jdbc.update("INSERT INTO guest_matches (id, event_id) VALUES (?, ?)", UUID.randomUUID(), staleId);
        faceService.sweepOrphanCollections();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guest_matches WHERE event_id = ?", Long.class, staleId)).isZero();
    }

    // ---- Studio stats ----

    private ResultActions analytics(String eventId) throws Exception {
        return mvc.perform(get("/api/admin/events/" + eventId + "/analytics").header("Authorization", bearer(adminToken())));
    }

    @Test
    void faceSearchStatsCountSearchesFindsUpdatesAndWhatCameOfThem() throws Exception {
        String event = createEvent("Stats", "LIVE");
        String id = eventId(event);
        String slug = slug(event);
        String up = createUploader(id, "cam");
        analytics(id).andExpect(status().isOk()).andExpect(jsonPath("$.faceSearch").doesNotExist()); // add-on off: no section
        setFaceSearch(id, true);
        String a = photo(id, up, "alice");
        photo(id, up, "alice");
        indexAll();
        analytics(id).andExpect(jsonPath("$.faceSearch.searches").value(0));

        String visitor = "visitor-face-0001";
        String matchId = matchIdOf(mvc.perform(multipart("/api/public/events/" + slug + "/find-me")
                .file(new MockMultipartFile("selfie", "s.jpg", "image/jpeg", selfieOf("alice"))).param("visitorId", visitor)
                .with(r -> {
                    r.setRemoteAddr(ip());
                    return r;
                })));
        findMe(slug, selfieOf("nobody"), ip()).andExpect(status().isOk()); // a search that finds no one
        byte[] none = jpeg(90, 90);
        fake.selfieHasNoFace(none);
        findMe(slug, none, ip()).andExpect(status().isBadRequest()); // a failed search
        photo(id, up, "alice");
        indexAll();
        refresh(slug, matchId, ip()).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(3));
        refresh(slug, matchId, ip()).andExpect(status().isOk()); // nothing new: not an update
        // Downloads and likes from the face results are reported by the browser with source "face".
        mvc.perform(post("/api/public/events/" + slug + "/activity").contentType(MediaType.APPLICATION_JSON)
                .content("{\"visitorId\":\"" + visitor + "\",\"items\":[{\"type\":\"DOWNLOAD\",\"photoId\":\"" + a + "\",\"source\":\"face\"},"
                        + "{\"type\":\"LIKE\",\"photoId\":\"" + a + "\",\"source\":\"face\"},"
                        + "{\"type\":\"DOWNLOAD\",\"photoId\":\"" + a + "\"},{\"type\":\"FACE_FOUND\"}]}"))
                .andExpect(status().isNoContent());

        analytics(id).andExpect(status().isOk()).andExpect(jsonPath("$.faceSearch.searches").value(3))
                .andExpect(jsonPath("$.faceSearch.failed").value(1)).andExpect(jsonPath("$.faceSearch.guestsFound").value(1))
                .andExpect(jsonPath("$.faceSearch.photosFound").value(3)) // 2 found at first + 1 added by the update
                .andExpect(jsonPath("$.faceSearch.updates").value(1)).andExpect(jsonPath("$.faceSearch.downloads").value(1))
                .andExpect(jsonPath("$.faceSearch.likes").value(1))
                // The usual numbers are not distorted by the face rows (a guest cannot forge FACE_* types either).
                .andExpect(jsonPath("$.totals.downloads").value(2)).andExpect(jsonPath("$.totals.people").value(1));
        setFaceSearch(id, false);
        analytics(id).andExpect(jsonPath("$.faceSearch").doesNotExist());
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
        trustStudio(studioId);
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
    void aPrivateGalleryShowsEachGuestOnlyTheirOwnPhotos() throws Exception {
        String event = createEvent("Private", "LIVE");
        String id = eventId(event);
        String slug = slug(event);
        String up = createUploader(id, "cam");
        // It needs face search.
        mvc.perform(put("/api/admin/events/" + id + "/face-search").header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"privateGallery\":true}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FACE_SEARCH_OFF"));
        setFaceSearch(id, true);
        String alice = photo(id, up, "alice");
        String bob = photo(id, up, "bob");
        indexAll();
        mvc.perform(put("/api/admin/events/" + id + "/face-search").header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"privateGallery\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.privateGallery").value(true));

        mvc.perform(get("/api/public/events/" + slug)).andExpect(jsonPath("$.privateGallery").value(true))
                .andExpect(jsonPath("$.faceSearch").value(true));
        // Nothing lists the whole gallery.
        mvc.perform(get("/api/public/events/" + slug + "/photos")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVATE_GALLERY"));
        mvc.perform(get("/api/public/events/" + slug + "/stream")).andExpect(status().isForbidden());
        mvc.perform(get("/api/public/events/" + slug + "/categories")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/public/events/" + slug + "/photos/" + alice + "/download")).andExpect(status().isNotFound());

        String found = findMe(slug, selfieOf("alice"), ip()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(found, "$.photos[*].id")).containsExactly(alice);
        String match = JsonPath.read(found, "$.matchId");
        // Downloads: only the guest's own photos, and only with their match.
        mvc.perform(get("/api/public/events/" + slug + "/photos/" + alice + "/download").param("match", match)).andExpect(status().isOk());
        mvc.perform(get("/api/public/events/" + slug + "/photos/" + bob + "/download").param("match", match)).andExpect(status().isNotFound());

        // Turning face search off ends the private gallery too: everyone sees the whole gallery again.
        setFaceSearch(id, false);
        mvc.perform(get("/api/public/events/" + slug)).andExpect(jsonPath("$.privateGallery").value(false));
        mvc.perform(get("/api/public/events/" + slug + "/photos")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT face_private FROM events WHERE id = ?", Boolean.class, UUID.fromString(id))).isFalse();
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
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("FACE_SEARCH_NOT_PAID"));
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
        // Paid: the studio switches it off and on itself, and is never charged twice.
        mvc.perform(put("/api/admin/events/" + main + "/face-search").header("Authorization", bearer(studio))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.paid").value(true));
        quote(studio, main).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_PAID"));
        mvc.perform(put("/api/admin/events/" + main + "/face-search").header("Authorization", bearer(studio))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

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
