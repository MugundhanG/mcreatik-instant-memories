package com.mcreatik.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.mcreatik.gallery.support.IntegrationTest;

/** Plans: the monthly allowance, extra events bought by UPI, McreatiK's checks. */
class BillingTest extends IntegrationTest {

    private static final AtomicInteger IPS = new AtomicInteger();
    private String studioId;
    private String studio;

    @BeforeEach
    void studioOnMonthlyPlan() throws Exception {
        jdbc.update("DELETE FROM event_payments");
        mvc.perform(put("/api/admin/billing/settings").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upiId\":\"mcreatik@okicici\",\"payeeName\":\"McreatiK\",\"perEventPrice\":2500,\"extraEventPrice\":999,\"monthlyPrice\":5000}"))
                .andExpect(status().isOk());
        String email = "plan-" + UUID.randomUUID().toString().substring(0, 8) + "@client.test";
        studioId = JsonPath.read(mvc.perform(post("/api/admin/studios").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lens\",\"email\":\"" + email + "\",\"password\":\"temporary-123\",\"plan\":\"MONTHLY\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(patch("/api/admin/billing/studios/" + studioId).header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"monthlyQuota\":2}")).andExpect(status().isOk());
        int n = IPS.incrementAndGet();
        studio = JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"temporary-123\"}")
                        .with(r -> {
                            r.setRemoteAddr("10.66.0." + n);
                            return r;
                        }))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
    }

    @Test
    void thePublicPricingPageReadsTheCurrentPricesWithoutALogin() throws Exception {
        mvc.perform(get("/api/public/pricing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perEventPrice").isNumber())
                .andExpect(jsonPath("$.monthlyEvents").value(4))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.upiId").doesNotExist());
    }

    @Test
    void noOnlinePaymentEndpointsExistAndTheUpiQrIsTheOnlyFlow() throws Exception {
        mvc.perform(post("/api/admin/billing/online-orders").header("Authorization", bearer(studio))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"events\":1}"))
                .andExpect(status().is4xxClientError());
        mvc.perform(post("/api/admin/billing/upi-qr").header("Authorization", bearer(studio))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"events\":1}"))
                .andExpect(status().is4xxClientError());
        mvc.perform(post("/api/public/razorpay/webhook").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
        pay(1, "418299301122").andExpect(status().isCreated()).andExpect(jsonPath("$.method").value("UPI_QR"));
    }

    private ResultActions createEvent(String token) throws Exception {
        return mvc.perform(post("/api/admin/events").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Wedding\",\"eventDate\":\"2026-12-01\"}"));
    }

    private ResultActions pay(int events, String utr) throws Exception {
        return mvc.perform(post("/api/admin/billing/payments").header("Authorization", bearer(studio))
                .contentType(MediaType.APPLICATION_JSON).content("{\"events\":" + events + ",\"utr\":\"" + utr + "\"}"));
    }

    @Test
    void monthlyAllowanceThenPaidExtraEvents() throws Exception {
        String first = eventId(createEvent(studio).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        createEvent(studio).andExpect(status().isCreated());
        // A studio can't delete an event; McreatiK deleting it does not give the allowance back.
        mvc.perform(delete("/api/admin/events/" + first).header("Authorization", bearer(studio))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/events/" + first).header("Authorization", bearer(adminToken()))).andExpect(status().isNoContent());
        createEvent(studio).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("EVENT_PAYMENT_REQUIRED"));
        mvc.perform(get("/api/admin/billing").header("Authorization", bearer(studio)))
                .andExpect(jsonPath("$.plan").value("MONTHLY"))
                .andExpect(jsonPath("$.usedThisMonth").value(2))
                .andExpect(jsonPath("$.monthlyQuota").value(2))
                .andExpect(jsonPath("$.unitPrice").value(999))
                .andExpect(jsonPath("$.upiId").value("mcreatik@okicici"));

        // Two extra events by UPI: usable at once.
        pay(2, "4182 9930 1122").andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(1998))
                .andExpect(jsonPath("$.utr").value("418299301122"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        pay(1, "418299301122").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UTR_USED"));
        createEvent(studio).andExpect(status().isCreated());
        mvc.perform(get("/api/admin/billing").header("Authorization", bearer(studio))).andExpect(jsonPath("$.credits").value(1));
    }

    @Test
    void mcreatikApprovesOrRejectsPayments() throws Exception {
        String ok = JsonPath.read(pay(1, "UTR11112222").andReturn().getResponse().getContentAsString(), "$.id");
        String bad = JsonPath.read(pay(2, "UTR33334444").andReturn().getResponse().getContentAsString(), "$.id");
        String pending = mvc.perform(get("/api/admin/billing/payments?status=PENDING").header("Authorization", bearer(adminToken())))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<java.util.List<String>>read(pending, "$[*].id")).contains(ok, bad);
        mvc.perform(post("/api/admin/billing/payments/" + ok + "/approve").header("Authorization", bearer(adminToken())))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/admin/billing/payments/" + bad + "/reject").header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Not in the bank statement\"}")).andExpect(status().isNoContent());
        // 3 bought, 2 taken back.
        mvc.perform(get("/api/admin/billing").header("Authorization", bearer(studio)))
                .andExpect(jsonPath("$.credits").value(1))
                .andExpect(jsonPath("$.payments[?(@.utr == 'UTR33334444')].status").value("REJECTED"));
        mvc.perform(post("/api/admin/billing/payments/" + ok + "/approve").header("Authorization", bearer(adminToken())))
                .andExpect(status().isConflict());
        // Studios cannot review payments.
        mvc.perform(get("/api/admin/billing/payments").header("Authorization", bearer(studio))).andExpect(status().isForbidden());
    }

    @Test
    void perEventPlanAndMcreatikIsNeverLimited() throws Exception {
        mvc.perform(patch("/api/admin/billing/studios/" + studioId).header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PER_EVENT\"}")).andExpect(status().isOk());
        createEvent(studio).andExpect(status().isPaymentRequired());
        mvc.perform(get("/api/admin/billing").header("Authorization", bearer(studio))).andExpect(jsonPath("$.unitPrice").value(2500));
        // McreatiK gives a free event (e.g. a trial).
        mvc.perform(patch("/api/admin/billing/studios/" + studioId).header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"addCredits\":1}")).andExpect(jsonPath("$.credits").value(1));
        createEvent(studio).andExpect(status().isCreated());
        createEvent(studio).andExpect(status().isPaymentRequired());
        for (int i = 0; i < 3; i++) {
            createEvent(adminToken()).andExpect(status().isCreated());
        }
    }
}
