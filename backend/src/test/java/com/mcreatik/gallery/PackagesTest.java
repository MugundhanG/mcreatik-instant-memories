package com.mcreatik.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Date;
import java.sql.Timestamp;
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
import com.mcreatik.gallery.billing.BillingService;
import com.mcreatik.gallery.support.IntegrationTest;

/** Main and Quick events, monthly packs, carry-over, renewals, upgrades and typed purchases. */
class PackagesTest extends IntegrationTest {

    private static final AtomicInteger IPS = new AtomicInteger();
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @Autowired
    private BillingService billing;

    private String studioId;
    private String studio;

    @BeforeEach
    void perEventStudio() throws Exception {
        jdbc.update("DELETE FROM event_payments");
        mvc.perform(put("/api/admin/billing/settings").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"upiId":"mcreatik@okicici","payeeName":"McreatiK","perEventPrice":2499,"extraEventPrice":999,
                                 "monthlyPrice":5000,"quickPrice":1499,"main2Price":3499,"main4Price":4999,"quick2Price":2499,
                                 "quick4Price":3999,"upgradePrice":1000,"upgradeGuests":300}"""))
                .andExpect(status().isOk());
        String email = "pack-" + UUID.randomUUID().toString().substring(0, 8) + "@client.test";
        studioId = JsonPath.read(mvc.perform(post("/api/admin/studios").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lens\",\"email\":\"" + email + "\",\"password\":\"temporary-123\",\"plan\":\"PER_EVENT\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        int n = IPS.incrementAndGet();
        studio = JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"temporary-123\"}")
                        .with(r -> {
                            r.setRemoteAddr("10.77.0." + n);
                            return r;
                        }))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
    }

    private ResultActions event(String type) throws Exception {
        return mvc.perform(post("/api/admin/events").header("Authorization", bearer(studio)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Arun & Priya\",\"eventDate\":\"2026-12-01\",\"eventType\":\"" + type + "\"}"));
    }

    private ResultActions buy(String body) throws Exception {
        return mvc.perform(post("/api/admin/billing/payments").header("Authorization", bearer(studio))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void setPlan(String plan) throws Exception {
        mvc.perform(patch("/api/admin/billing/studios/" + studioId).header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"" + plan + "\"}")).andExpect(status().isOk());
    }

    private String summary() throws Exception {
        return mvc.perform(get("/api/admin/billing").header("Authorization", bearer(studio)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String utr() {
        return "UTR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    @Test
    void perEventStudiosPayMainAndQuickPricesAndQuickCreditsDoNotCoverMain() throws Exception {
        event("MAIN").andExpect(status().isPaymentRequired()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("₹2,499")));
        event("QUICK").andExpect(status().isPaymentRequired()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("₹1,499")));

        buy("{\"item\":\"QUICK_EVENT\",\"quantity\":2,\"utr\":\"" + utr() + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.item").value("QUICK_EVENT"))
                .andExpect(jsonPath("$.amount").value(2998));
        event("MAIN").andExpect(status().isPaymentRequired()); // a Quick credit never covers a Main event
        String quick = eventId(event("QUICK").andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventType").value("QUICK")).andReturn().getResponse().getContentAsString());
        assertThat(quick).isNotBlank();

        // A Main credit covers a Quick event once the Quick credits are used up.
        buy("{\"item\":\"MAIN_EVENT\",\"quantity\":1,\"utr\":\"" + utr() + "\"}").andExpect(jsonPath("$.amount").value(2499));
        event("QUICK").andExpect(status().isCreated()); // the last Quick credit
        event("QUICK").andExpect(status().isCreated()); // the Main credit
        String s = summary();
        assertThat((Integer) JsonPath.read(s, "$.mainCredits")).isZero();
        assertThat((Integer) JsonPath.read(s, "$.quickCredits")).isZero();
        assertThat((Integer) JsonPath.read(s, "$.prices.quick4")).isEqualTo(3999);
    }

    @Test
    void mainPacksCoverQuickEventsAndQuickPacksDoNot() throws Exception {
        setPlan("MAIN_2");
        event("QUICK").andExpect(status().isCreated());
        event("MAIN").andExpect(status().isCreated());
        event("MAIN").andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("used your 2 events")));
        String s = summary();
        assertThat((Integer) JsonPath.read(s, "$.usedThisMonth")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(s, "$.monthlyQuota")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(s, "$.monthlyPrice")).isEqualTo(3499);
        assertThat((Boolean) JsonPath.read(s, "$.packActive")).isTrue();

        setPlan("QUICK_4");
        event("MAIN").andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Quick events only")));
    }

    @Test
    void extraEventsOnAPaidUpPackCostThePacksExtraPrice() throws Exception {
        // Main 4: ₹999 for an extra Main or Quick event.
        setPlan("MAIN_4");
        for (int i = 0; i < 4; i++) {
            event("MAIN").andExpect(status().isCreated());
        }
        event("QUICK").andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Main 4-event pack are ₹999 each")));
        String s = summary();
        assertThat((Integer) JsonPath.read(s, "$.unitPrice")).isEqualTo(999);
        assertThat((Integer) JsonPath.read(s, "$.quickUnitPrice")).isEqualTo(999);
        buy("{\"item\":\"MAIN_EVENT\",\"quantity\":2,\"utr\":\"" + utr() + "\"}").andExpect(jsonPath("$.amount").value(1998));
        event("QUICK").andExpect(status().isCreated());

        // Main 2: ₹1,499.
        setPlan("MAIN_2");
        assertThat((Integer) JsonPath.read(summary(), "$.unitPrice")).isEqualTo(1499);
        assertThat((Integer) JsonPath.read(summary(), "$.quickUnitPrice")).isEqualTo(1499);

        // Quick packs: Quick extras only; a Main event stays at the full price.
        setPlan("QUICK_4");
        s = summary();
        assertThat((Integer) JsonPath.read(s, "$.quickUnitPrice")).isEqualTo(799);
        assertThat((Integer) JsonPath.read(s, "$.unitPrice")).isEqualTo(2499);
        buy("{\"item\":\"QUICK_EVENT\",\"quantity\":1,\"utr\":\"" + utr() + "\"}").andExpect(jsonPath("$.amount").value(799));
        buy("{\"item\":\"MAIN_EVENT\",\"quantity\":1,\"utr\":\"" + utr() + "\"}").andExpect(jsonPath("$.amount").value(2499));
        setPlan("QUICK_2");
        assertThat((Integer) JsonPath.read(summary(), "$.quickUnitPrice")).isEqualTo(999);

        // A lapsed pack is back to the full prices.
        jdbc.update("UPDATE admin_users SET plan_paid_until = ? WHERE id = ?::uuid", Date.valueOf(LocalDate.now(INDIA).minusDays(4)), studioId);
        s = summary();
        assertThat((Integer) JsonPath.read(s, "$.quickUnitPrice")).isEqualTo(1499);
        assertThat((Integer) JsonPath.read(s, "$.unitPrice")).isEqualTo(2499);
        buy("{\"item\":\"QUICK_EVENT\",\"quantity\":1,\"utr\":\"" + utr() + "\"}").andExpect(jsonPath("$.amount").value(1499));
    }

    @Test
    void packExtraPricesAreSavedPublicAndMustBePositive() throws Exception {
        String body = """
                {"upiId":"mcreatik@okicici","payeeName":"McreatiK","perEventPrice":2499,"extraEventPrice":999,"monthlyPrice":5000,
                 "main2ExtraPrice":%d,"main4ExtraPrice":999,"quick2ExtraPrice":999,"quick4ExtraPrice":799}""";
        mvc.perform(put("/api/admin/billing/settings").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(body.formatted(1399)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.main2ExtraPrice").value(1399));
        mvc.perform(get("/api/public/pricing"))
                .andExpect(jsonPath("$.packages.main2Extra").value(1399))
                .andExpect(jsonPath("$.packages.main4Extra").value(999))
                .andExpect(jsonPath("$.packages.quick2Extra").value(999))
                .andExpect(jsonPath("$.packages.quick4Extra").value(799));
        mvc.perform(put("/api/admin/billing/settings").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(body.formatted(0)))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/billing/settings").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(body.formatted(1499)))
                .andExpect(status().isOk());
    }

    @Test
    void aNewPackStartsWithItsWholeAllowanceEvenAfterEventsOnTheOldPlanThisMonth() throws Exception {
        setPlan("MAIN_2");
        event("MAIN").andExpect(status().isCreated());
        event("MAIN").andExpect(status().isCreated());
        // Earlier today: those two were on the old pack (moved back a day so they fall before the new plan started).
        jdbc.update("UPDATE event_charges SET created_at = created_at - interval '1 day' WHERE owner_id = ?::uuid", studioId);
        // The app's "today" is India's date (the database's CURRENT_DATE is UTC and lags it after 6:30 pm UTC).
        jdbc.update("UPDATE admin_users SET plan_since = ? WHERE id = ?::uuid", Date.valueOf(LocalDate.now(INDIA)), studioId);
        boolean sameMonth = jdbc.queryForObject("SELECT date_trunc('month', now() - interval '1 day') = date_trunc('month', now())", Boolean.class);
        String s = summary();
        if (sameMonth) {
            assertThat((Integer) JsonPath.read(s, "$.usedThisMonth")).isZero();
        }
        mvc.perform(get("/api/admin/billing/studios").header("Authorization", bearer(adminToken())))
                .andExpect(jsonPath("$[?(@.studioId == '" + studioId + "')].usedThisMonth").value(org.hamcrest.Matchers.contains(0)));
    }

    @Test
    void unusedPackEventsCarryOverOneMonthOnly() throws Exception {
        setPlan("MAIN_2");
        LocalDate today = LocalDate.now(INDIA);
        // On the pack since two months ago; last month 1 of 2 events used, the month before none.
        jdbc.update("UPDATE admin_users SET plan_since = ? WHERE id = ?::uuid", Date.valueOf(today.minusMonths(2).withDayOfMonth(1)), studioId);
        jdbc.update("INSERT INTO event_charges (id, owner_id, kind, created_at) VALUES (?, ?::uuid, 'QUOTA', ?)",
                UUID.randomUUID(), studioId, Timestamp.from(today.minusMonths(1).withDayOfMonth(5).atStartOfDay(INDIA).toInstant()));
        assertThat((Integer) JsonPath.read(summary(), "$.carryOver")).isEqualTo(1);

        event("MAIN").andExpect(status().isCreated()); // carried over
        event("MAIN").andExpect(status().isCreated()); // this month 1/2
        event("MAIN").andExpect(status().isCreated()); // this month 2/2
        event("MAIN").andExpect(status().isPaymentRequired());
        assertThat(jdbc.queryForList("SELECT kind FROM event_charges WHERE owner_id = ?::uuid AND event_id IS NOT NULL ORDER BY created_at",
                String.class, studioId)).containsExactly("CARRY", "QUOTA", "QUOTA");

        // A pack that started this month has nothing to carry.
        jdbc.update("UPDATE admin_users SET plan_since = ? WHERE id = ?::uuid", Date.valueOf(today), studioId);
        assertThat((Integer) JsonPath.read(summary(), "$.carryOver")).isZero();
    }

    @Test
    void aPackStopsAFewDaysAfterItsPaidDateAndRenewingExtendsIt() throws Exception {
        setPlan("MAIN_4");
        LocalDate today = LocalDate.now(INDIA);
        assertThat(LocalDate.parse(JsonPath.read(summary(), "$.planPaidUntil"))).isEqualTo(today.plusMonths(1));

        jdbc.update("UPDATE admin_users SET plan_paid_until = ? WHERE id = ?::uuid", Date.valueOf(today.minusDays(2)), studioId);
        event("MAIN").andExpect(status().isCreated()); // still within the grace days
        jdbc.update("UPDATE admin_users SET plan_paid_until = ? WHERE id = ?::uuid", Date.valueOf(today.minusDays(4)), studioId);
        event("MAIN").andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("pack ended")));
        assertThat((Boolean) JsonPath.read(summary(), "$.packActive")).isFalse();

        // Renewing a lapsed pack starts from today; paying again adds a month on top.
        String first = JsonPath.read(buy("{\"item\":\"PLAN\",\"utr\":\"" + utr() + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plan").value("MAIN_4"))
                .andExpect(jsonPath("$.amount").value(4999)).andReturn().getResponse().getContentAsString(), "$.id");
        assertThat(LocalDate.parse(JsonPath.read(summary(), "$.planPaidUntil"))).isEqualTo(today.plusMonths(1));
        buy("{\"item\":\"PLAN\",\"quantity\":2,\"utr\":\"" + utr() + "\"}").andExpect(jsonPath("$.amount").value(9998));
        assertThat(LocalDate.parse(JsonPath.read(summary(), "$.planPaidUntil"))).isEqualTo(today.plusMonths(3));
        event("MAIN").andExpect(status().isCreated());

        // McreatiK rejects a renewal that never arrived: that month is taken back.
        mvc.perform(post("/api/admin/billing/payments/" + first + "/reject").header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNoContent());
        assertThat(LocalDate.parse(JsonPath.read(summary(), "$.planPaidUntil"))).isEqualTo(today.plusMonths(2));
    }

    @Test
    void aPerEventStudioCanBuyAPackForItself() throws Exception {
        buy("{\"item\":\"PLAN\",\"plan\":\"QUICK_2\",\"utr\":\"" + utr() + "\"}").andExpect(jsonPath("$.amount").value(2499));
        String s = summary();
        assertThat((String) JsonPath.read(s, "$.plan")).isEqualTo("QUICK_2");
        event("QUICK").andExpect(status().isCreated());
        buy("{\"item\":\"PLAN\",\"plan\":\"PER_EVENT\",\"utr\":\"" + utr() + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PLAN"));
    }

    @Test
    void aQuickEventCanBeUpgradedToMainOnceAndARejectedUpgradeIsUndone() throws Exception {
        buy("{\"item\":\"QUICK_EVENT\",\"utr\":\"" + utr() + "\"}");
        String quick = eventId(event("QUICK").andReturn().getResponse().getContentAsString());
        String payment = JsonPath.read(buy("{\"item\":\"UPGRADE\",\"eventId\":\"" + quick + "\",\"utr\":\"" + utr() + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(1000))
                .andExpect(jsonPath("$.eventName").value("Arun & Priya")).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(get("/api/admin/events/" + quick).header("Authorization", bearer(studio))).andExpect(jsonPath("$.eventType").value("MAIN"));
        buy("{\"item\":\"UPGRADE\",\"eventId\":\"" + quick + "\",\"utr\":\"" + utr() + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_QUICK"));

        mvc.perform(post("/api/admin/billing/payments/" + payment + "/reject").header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/events/" + quick).header("Authorization", bearer(studio))).andExpect(jsonPath("$.eventType").value("QUICK"));

        // Another studio's event can't be upgraded by this one.
        String other = eventId(createEvent("McreatiK's own", "UPCOMING"));
        buy("{\"item\":\"UPGRADE\",\"eventId\":\"" + other + "\",\"utr\":\"" + utr() + "\"}").andExpect(status().isNotFound());
    }

    @Test
    void mcreatikGivesQuickEventsAndPricesArePublicAndKeptWhenOlderClientsSave() throws Exception {
        mvc.perform(patch("/api/admin/billing/studios/" + studioId).header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"addQuickCredits\":3}"))
                .andExpect(jsonPath("$.quickCredits").value(3)).andExpect(jsonPath("$.credits").value(3));
        // An older settings screen sends only the first five fields: the newer prices stay.
        mvc.perform(put("/api/admin/billing/settings").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upiId\":\"mcreatik@okicici\",\"payeeName\":\"McreatiK\",\"perEventPrice\":2499,\"extraEventPrice\":999,\"monthlyPrice\":5000}"))
                .andExpect(jsonPath("$.quick4Price").value(3999));
        mvc.perform(get("/api/public/pricing"))
                .andExpect(jsonPath("$.packages.main").value(2499))
                .andExpect(jsonPath("$.packages.quick").value(1499))
                .andExpect(jsonPath("$.packages.main4").value(4999))
                .andExpect(jsonPath("$.packages.upgrade").value(1000))
                .andExpect(jsonPath("$.packages.upgradeGuests").value(300));
    }

    @Test
    void packsDueForRenewalAreRemindedOnce() throws Exception {
        setPlan("MAIN_4");
        LocalDate due = LocalDate.now(INDIA).plusDays(2);
        jdbc.update("UPDATE admin_users SET plan_paid_until = ? WHERE id = ?::uuid", Date.valueOf(due), studioId);
        assertThat(billing.renewalsDue(3)).anySatisfy(r -> {
            assertThat(r.studioId().toString()).isEqualTo(studioId);
            assertThat(r.price()).isEqualTo(4999);
        });
        billing.markReminded(UUID.fromString(studioId), due);
        assertThat(billing.renewalsDue(3)).noneMatch(r -> r.studioId().toString().equals(studioId));
    }
}
