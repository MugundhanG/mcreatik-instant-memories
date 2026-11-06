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
        assertThat(LocalDate.parse(JsonPath.read(json, "$.activeUntil"))).isEqualTo(today.plusMonths(3));
        assertThat(LocalDate.parse(JsonPath.read(json, "$.retentionUntil"))).isEqualTo(today.plusMonths(3).plusDays(30));
        // One setup fee is one gallery.
        event("CLIENT", "2026-11-01").andExpect(status().isPaymentRequired());
        // No guest QR gallery.
        mvc.perform(get("/api/public/events/" + slug(json))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GALLERY_UNAVAILABLE"));
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
                .andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value(450)); // 10 GB × ₹15 × 3 months
        assertThat((Integer) JsonPath.read(detail(id), "$.storageQuotaGb")).isEqualTo(10);
        originalSession(id, 2_000_000).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("UPLOAD"));
        // Storage can't be bought for someone else's event.
        String other = eventId(createEvent("McreatiK's own", "UPCOMING"));
        buy("{\"item\":\"STORAGE\",\"eventId\":\"" + other + "\",\"quantity\":1,\"utr\":\"" + utr() + "\"}").andExpect(status().isNotFound());
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

        buy("{\"item\":\"STORAGE\",\"eventId\":\"" + id + "\",\"quantity\":1,\"utr\":\"" + utr() + "\"}").andExpect(status().isCreated());
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

        // Paying studios can't just move the date; they keep it open for 3 more months, at ₹15 per GB per month (at least 1 GB): ₹45.
        mvc.perform(patch("/api/admin/events/" + id).header("Authorization", bearer(studio))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"retentionUntil\":\"2030-01-01\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EXTEND_INSTEAD"));
        String pay = JsonPath.read(buy("{\"item\":\"EXTEND\",\"eventId\":\"" + id + "\",\"utr\":\"" + utr() + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(45)).andReturn().getResponse().getContentAsString(), "$.id");
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
