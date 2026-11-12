package com.mcreatik.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.mcreatik.gallery.common.Tokens;
import com.mcreatik.gallery.billing.BillingService;
import com.mcreatik.gallery.billing.GalleryLifecycleTestAccess;
import com.mcreatik.gallery.support.IntegrationTest;

/** Client galleries, paid storage, live events' free storage and the 6-month active period. */
class ClientGalleryTest extends IntegrationTest {

    private static final AtomicInteger IPS = new AtomicInteger();
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @Autowired
    private GalleryLifecycleTestAccess lifecycle;
    @Autowired
    private com.mcreatik.gallery.event.EventService events;

    private String studioId;
    private String studio;

    @BeforeEach
    void perEventStudio() throws Exception {
        jdbc.update("DELETE FROM event_payments");
        mvc.perform(put("/api/admin/billing/settings").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"upiId":"mcreatik@okicici","payeeName":"McreatiK","perEventPrice":2499,"extraEventPrice":999,
                                 "monthlyPrice":5000,"storageGbPrice":15,"freeStorageGb":5,"clientSetupPrice":250,"activeMonths":3}"""))
                .andExpect(status().isOk());
        String email = "client-" + UUID.randomUUID().toString().substring(0, 8) + "@studio.test";
        studioId = JsonPath.read(mvc.perform(post("/api/admin/studios").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lens\",\"email\":\"" + email + "\",\"password\":\"temporary-123\",\"plan\":\"PER_EVENT\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        int n = IPS.incrementAndGet();
        studio = JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"temporary-123\"}")
                        .with(r -> {
                            r.setRemoteAddr("10.78.0." + n);
                            return r;
                        }))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
    }

    private ResultActions event(String type, String date) throws Exception {
        return mvc.perform(post("/api/admin/events").header("Authorization", bearer(studio)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Arun & Priya\",\"eventDate\":\"" + date + "\",\"eventType\":\"" + type + "\",\"status\":\"LIVE\"}"));
    }

    private ResultActions buy(String body) throws Exception {
        return mvc.perform(post("/api/admin/billing/payments").header("Authorization", bearer(studio))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String utr() {
        return "UTR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private ResultActions originalSession(String eventId, long size) throws Exception {
        byte[] marker = UUID.randomUUID().toString().getBytes();
        return mvc.perform(post("/api/admin/events/" + eventId + "/original-uploads").header("Authorization", bearer(studio))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileName\":\"IMG_1.JPG\",\"fileSize\":" + size + ",\"mimeType\":\"image/jpeg\",\"checksumSha256\":\""
                        + Tokens.sha256Hex(marker) + "\"}"));
    }

    private String detail(String eventId) throws Exception {
        return mvc.perform(get("/api/admin/events/" + eventId).header("Authorization", bearer(studio)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void aClientGalleryIsPaidPerGalleryHasNoGuestGalleryAndStartsWithoutStorage() throws Exception {
        event("CLIENT", "2026-11-01").andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("CLIENT_PAYMENT_REQUIRED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("₹250")));
        buy("{\"item\":\"CLIENT_SETUP\",\"quantity\":1,\"utr\":\"" + utr() + "\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value(250));
        String json = event("CLIENT", "2026-11-01").andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventType").value("CLIENT"))
                .andExpect(jsonPath("$.liveGalleryEnabled").value(false))
                .andExpect(jsonPath("$.storageQuotaGb").value(0)).andReturn().getResponse().getContentAsString();
        LocalDate today = LocalDate.now(INDIA);
        assertThat(LocalDate.parse(JsonPath.read(json, "$.activeUntil"))).isEqualTo(today.plusMonths(1));
        assertThat(LocalDate.parse(JsonPath.read(json, "$.retentionUntil"))).isEqualTo(today.plusMonths(1).plusDays(30));
        // One setup fee is one gallery.
        event("CLIENT", "2026-11-01").andExpect(status().isPaymentRequired());
        // No guest QR gallery.
        mvc.perform(get("/api/public/events/" + slug(json))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GALLERY_UNAVAILABLE"));
    }

    @Test
    void storageBoughtWithTheSetupGoesToTheNewGalleryAndARejectedPaymentTakesItBack() throws Exception {
        // ₹250 setup + 10 GB × ₹15 × 3 months = ₹700, in one payment; the gallery opens for those 3 months.
        buy("{\"item\":\"CLIENT_SETUP\",\"quantity\":1,\"storageGb\":10,\"months\":3,\"utr\":\"" + utr() + "\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value(700)).andExpect(jsonPath("$.months").value(3));
        String threeMonths = event("CLIENT", "2026-11-01").andExpect(status().isCreated())
                .andExpect(jsonPath("$.storageQuotaGb").value(10)).andReturn().getResponse().getContentAsString();
        assertThat(LocalDate.parse(JsonPath.read(threeMonths, "$.activeUntil"))).isEqualTo(LocalDate.now(INDIA).plusMonths(3));
        // Used once: the next gallery starts without storage.
        buy("{\"item\":\"CLIENT_SETUP\",\"utr\":\"" + utr() + "\"}").andExpect(jsonPath("$.amount").value(250));
        event("CLIENT", "2026-11-01").andExpect(status().isCreated()).andExpect(jsonPath("$.storageQuotaGb").value(0));
        // Months: at least 1, at most 12; storage at least 5 GB (or none).
        buy("{\"item\":\"CLIENT_SETUP\",\"storageGb\":5,\"months\":13,\"utr\":\"" + utr() + "\"}").andExpect(status().isBadRequest());
        buy("{\"item\":\"CLIENT_SETUP\",\"storageGb\":4,\"utr\":\"" + utr() + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GB"));
        // Storage only with a single gallery; a rejected payment takes its storage back.
        buy("{\"item\":\"CLIENT_SETUP\",\"quantity\":2,\"storageGb\":5,\"utr\":\"" + utr() + "\"}").andExpect(status().isBadRequest());
        String id = JsonPath.read(buy("{\"item\":\"CLIENT_SETUP\",\"storageGb\":5,\"utr\":\"" + utr() + "\"}")
                .andExpect(jsonPath("$.amount").value(325)).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/admin/billing/payments/" + id + "/reject").header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT client_storage_gb FROM admin_users WHERE id = ?::uuid", Integer.class, studioId)).isZero();
        event("CLIENT", "2026-11-01").andExpect(status().isPaymentRequired());
    }

    @Test
    void extendingChoosesTheMonthsAndChargesAllTheStorageForThem() throws Exception {
        buy("{\"item\":\"MAIN_EVENT\",\"utr\":\"" + utr() + "\"}");
        String id = eventId(event("MAIN", "2026-11-01").andReturn().getResponse().getContentAsString());
        // The 5 GB x 3 months every Main event includes are free; the quote tells the page about them.
        mvc.perform(post("/api/admin/billing/quote").header("Authorization", bearer(studio)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"EXTEND\",\"eventId\":\"" + id + "\",\"months\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(150)).andExpect(jsonPath("$.months").value(2))
                .andExpect(jsonPath("$.includedGb").value(5)).andExpect(jsonPath("$.includedMonths").value(3));
        // Bought storage counts: 5 + 10 GB for 1 month = 225.
        buy("{\"item\":\"STORAGE\",\"eventId\":\"" + id + "\",\"quantity\":10,\"utr\":\"" + utr() + "\"}").andExpect(status().isCreated());
        buy("{\"item\":\"EXTEND\",\"eventId\":\"" + id + "\",\"months\":1,\"utr\":\"" + utr() + "\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value(225));
        buy("{\"item\":\"EXTEND\",\"eventId\":\"" + id + "\",\"months\":0,\"utr\":\"" + utr() + "\"}").andExpect(status().isCreated()); // 0 = the default (3)
        buy("{\"item\":\"EXTEND\",\"eventId\":\"" + id + "\",\"months\":13,\"utr\":\"" + utr() + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_MONTHS"));
        buy("{\"item\":\"EXTEND\",\"eventId\":\"" + id + "\",\"months\":-1,\"utr\":\"" + utr() + "\"}").andExpect(status().isBadRequest());
    }

    @Test
    void whenRetentionEndsThePhotosGoButTheEventRecordStaysArchived() throws Exception {
        buy("{\"item\":\"MAIN_EVENT\",\"utr\":\"" + utr() + "\"}");
        String id = eventId(event("MAIN", "2026-11-01").andReturn().getResponse().getContentAsString());
        uploadPhoto(createUploader(id, "Camera 1"), "IMG_1.JPG", jpeg(800, 600));
        processAll();
        UUID eventId = UUID.fromString(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM photos WHERE event_id = ?", Integer.class, eventId)).isEqualTo(1);
        LocalDate today = LocalDate.now(INDIA);
        jdbc.update("UPDATE events SET active_until = ?, retention_until = ? WHERE id = ?",
                Date.valueOf(today.minusDays(40)), Date.valueOf(today.minusDays(10)), eventId);

        for (var expired : events.expiredEvents(today)) {
            events.deleteExpired(expired);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM photos WHERE event_id = ?", Integer.class, eventId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM events WHERE id = ?", String.class, eventId)).isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForObject("SELECT photos_deleted_at IS NOT NULL FROM events WHERE id = ?", Boolean.class, eventId)).isTrue();
        // The event record, its payment and its charge stay as the business record, and it is not purged twice.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_charges WHERE event_id = ?", Integer.class, eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_payments WHERE studio_id = ?::uuid", Integer.class, studioId)).isEqualTo(1);
        assertThat(events.expiredEvents(today)).noneMatch(e -> e.getId().equals(eventId));
        String archived = detail(id);
        assertThat((String) JsonPath.read(archived, "$.status")).isEqualTo("ARCHIVED");
        // Its files are gone, so it can't be kept open any longer.
        buy("{\"item\":\"EXTEND\",\"eventId\":\"" + id + "\",\"utr\":\"" + utr() + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_DELETED"));
        // Only McreatiK can remove the record itself.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/admin/events/" + id)
                .header("Authorization", bearer(studio))).andExpect(status().isForbidden());
    }

    @Test
    void storageIsChargedForTheMonthsAGalleryHasLeft() {
        LocalDate d = LocalDate.of(2026, 11, 6);
        assertThat(BillingService.monthsLeft(d.plusMonths(6), d, 6)).isEqualTo(6);
        assertThat(BillingService.monthsLeft(d.plusMonths(2).plusDays(1), d, 6)).isEqualTo(3);
        assertThat(BillingService.monthsLeft(d.plusDays(3), d, 6)).isEqualTo(1);
        assertThat(BillingService.monthsLeft(d.minusDays(3), d, 6)).isEqualTo(1);
        assertThat(BillingService.monthsLeft(null, d, 6)).isEqualTo(6);
    }

    @Test
    void aFullClientGalleryRefusesUploadsUntilStorageIsBought() throws Exception {
        buy("{\"item\":\"CLIENT_SETUP\",\"utr\":\"" + utr() + "\"}");
        String id = eventId(event("CLIENT", "2026-11-01").andReturn().getResponse().getContentAsString());
        originalSession(id, 2_000_000).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STORAGE_FULL"));

        buy("{\"item\":\"STORAGE\",\"eventId\":\"" + id + "\",\"quantity\":10,\"utr\":\"" + utr() + "\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value(150)); // 10 GB × ₹15 × 1 month left
        assertThat((Integer) JsonPath.read(detail(id), "$.storageQuotaGb")).isEqualTo(10);
        originalSession(id, 2_000_000).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("UPLOAD"));
        // Storage can't be bought for someone else's event.
        String other = eventId(createEvent("McreatiK's own", "UPCOMING"));
        buy("{\"item\":\"STORAGE\",\"eventId\":\"" + other + "\",\"quantity\":5,\"utr\":\"" + utr() + "\"}").andExpect(status().isNotFound());
        // Custom sizes start at 5 GB.
        buy("{\"item\":\"STORAGE\",\"eventId\":\"" + id + "\",\"quantity\":4,\"utr\":\"" + utr() + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GB"));
    }

    @Test
    void liveEventsAreNeverStoppedButTheirCouplesGalleryWaitsForUnpaidStorage() throws Exception {
        buy("{\"item\":\"MAIN_EVENT\",\"utr\":\"" + utr() + "\"}");
        LocalDate eventDay = LocalDate.now(INDIA).minusDays(20);
        String id = eventId(event("MAIN", eventDay.toString()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.storageQuotaGb").value(5)).andReturn().getResponse().getContentAsString());
        jdbc.update("UPDATE events SET storage_quota_gb = 0 WHERE id = ?::uuid", id);

        // A camera upload goes through even with no storage left.
        String camera = createUploader(id, "Camera 1");
        uploadPhoto(camera, "IMG_1.JPG", jpeg(800, 600));
        processAll();

        lifecycle.lockUnpaidStorage();
        String locked = detail(id);
        assertThat((Boolean) JsonPath.read(locked, "$.clientGalleryEnabled")).isFalse();
        assertThat((Boolean) JsonPath.read(locked, "$.storageLocked")).isTrue();
        assertThat((Boolean) JsonPath.read(locked, "$.liveGalleryEnabled")).isTrue();

        buy("{\"item\":\"STORAGE\",\"eventId\":\"" + id + "\",\"quantity\":5,\"utr\":\"" + utr() + "\"}").andExpect(status().isCreated());
        String open = detail(id);
        assertThat((Boolean) JsonPath.read(open, "$.clientGalleryEnabled")).isTrue();
        assertThat((Boolean) JsonPath.read(open, "$.storageLocked")).isFalse();

        // An event still within its 14 days isn't touched.
        buy("{\"item\":\"MAIN_EVENT\",\"utr\":\"" + utr() + "\"}");
        String recent = eventId(event("MAIN", LocalDate.now(INDIA).minusDays(3).toString()).andReturn().getResponse().getContentAsString());
        jdbc.update("UPDATE events SET storage_quota_gb = 0 WHERE id = ?::uuid", recent);
        uploadPhoto(createUploader(recent, "Camera 1"), "IMG_2.JPG", jpeg(800, 600));
        processAll();
        lifecycle.lockUnpaidStorage();
        assertThat((Boolean) JsonPath.read(detail(recent), "$.clientGalleryEnabled")).isTrue();
    }

    @Test
    void anExpiredGalleryClosesAndKeepingItSixMoreMonthsIsChargedPerGbAndReversible() throws Exception {
        buy("{\"item\":\"MAIN_EVENT\",\"utr\":\"" + utr() + "\"}");
        String json = event("MAIN", "2026-11-01").andReturn().getResponse().getContentAsString();
        String id = eventId(json);
        LocalDate today = LocalDate.now(INDIA);
        jdbc.update("UPDATE events SET active_until = ?, retention_until = ? WHERE id = ?::uuid",
                Date.valueOf(today.minusDays(1)), Date.valueOf(today.plusDays(29)), id);
        mvc.perform(get("/api/public/events/" + slug(json))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GALLERY_EXPIRED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("contact Lens")));
        assertThat((Boolean) JsonPath.read(detail(id), "$.expired")).isTrue();

        // Paying studios can't just move the date; they keep it open for 3 more months, at ₹15 per GB of its 5 GB quota per month: ₹225.
        mvc.perform(patch("/api/admin/events/" + id).header("Authorization", bearer(studio))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"retentionUntil\":\"2030-01-01\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EXTEND_INSTEAD"));
        String pay = JsonPath.read(buy("{\"item\":\"EXTEND\",\"eventId\":\"" + id + "\",\"utr\":\"" + utr() + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(225)).andExpect(jsonPath("$.months").value(3)).andReturn().getResponse().getContentAsString(), "$.id");
        String extended = detail(id);
        assertThat(LocalDate.parse(JsonPath.read(extended, "$.activeUntil"))).isEqualTo(today.plusMonths(3));
        assertThat(LocalDate.parse(JsonPath.read(extended, "$.retentionUntil"))).isEqualTo(today.plusMonths(3).plusDays(30));
        mvc.perform(get("/api/public/events/" + slug(json))).andExpect(status().isOk());

        // McreatiK rejects the payment: the 3 months are taken back.
        mvc.perform(post("/api/admin/billing/payments/" + pay + "/reject").header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertThat(LocalDate.parse(JsonPath.read(detail(id), "$.activeUntil"))).isEqualTo(today);
        // McreatiK can still set any date.
        mvc.perform(patch("/api/admin/events/" + id).header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"retentionUntil\":\"2030-01-01\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activeUntil").value("2030-01-01"));
    }

    @Test
    void studiosAreEmailedOnceBeforeAndOnceAfterAGalleryCloses() throws Exception {
        buy("{\"item\":\"MAIN_EVENT\",\"utr\":\"" + utr() + "\"}");
        String id = eventId(event("MAIN", "2026-11-01").andReturn().getResponse().getContentAsString());
        LocalDate today = LocalDate.now(INDIA);
        jdbc.update("UPDATE events SET active_until = ? WHERE id = ?::uuid", Date.valueOf(today.plusDays(10)), id);
        lifecycle.warnBeforeExpiry();
        assertThat(jdbc.queryForObject("SELECT expiry_warned_for FROM events WHERE id = ?::uuid", LocalDate.class, id))
                .isEqualTo(today.plusDays(10));
        jdbc.update("UPDATE events SET active_until = ? WHERE id = ?::uuid", Date.valueOf(today.minusDays(1)), id);
        lifecycle.noticeExpired();
        assertThat(jdbc.queryForObject("SELECT expiry_notified_for FROM events WHERE id = ?::uuid", LocalDate.class, id))
                .isEqualTo(today.minusDays(1));
    }

    @Test
    void storagePricesArePublic() throws Exception {
        mvc.perform(get("/api/public/pricing"))
                .andExpect(jsonPath("$.packages.storageGb").value(15))
                .andExpect(jsonPath("$.packages.freeStorageGb").value(5))
                .andExpect(jsonPath("$.packages.clientSetup").value(250))
                .andExpect(jsonPath("$.packages.activeMonths").value(3));
        mvc.perform(patch("/api/admin/billing/studios/" + studioId).header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"addClientCredits\":2}"))
                .andExpect(jsonPath("$.clientCredits").value(2));
    }
}
