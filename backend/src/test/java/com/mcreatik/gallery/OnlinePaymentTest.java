package com.mcreatik.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.jayway.jsonpath.JsonPath;
import com.mcreatik.gallery.support.IntegrationTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Buying events online with Razorpay (a stub of Razorpay's API stands in; no real money). */
class OnlinePaymentTest extends IntegrationTest {

    static final String KEY_SECRET = "test_key_secret";
    static final String WEBHOOK_SECRET = "test_webhook_secret";
    static final HttpServer RAZORPAY;
    /** order id -> amount in paise, as created */
    static final Map<String, Long> ORDERS = new ConcurrentHashMap<>();
    /** payment id -> {order id, amount, status} that the stub reports */
    static final Map<String, Object[]> PAYMENTS = new ConcurrentHashMap<>();
    /** QR id -> {amount in paise, payments made [payment json...], closed} */
    static final Map<String, Object[]> QRS = new ConcurrentHashMap<>();
    static final AtomicInteger IDS = new AtomicInteger();
    static final AtomicInteger IPS = new AtomicInteger();

    static {
        try {
            RAZORPAY = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        RAZORPAY.createContext("/", OnlinePaymentTest::razorpay);
        RAZORPAY.start();
    }

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry r) {
        r.add("gallery.razorpay.key-id", () -> "rzp_test_key");
        r.add("gallery.razorpay.key-secret", () -> KEY_SECRET);
        r.add("gallery.razorpay.webhook-secret", () -> WEBHOOK_SECRET);
        r.add("gallery.razorpay.api-base-url", () -> "http://127.0.0.1:" + RAZORPAY.getAddress().getPort());
    }

    private static void razorpay(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String expected = "Basic " + java.util.Base64.getEncoder().encodeToString(("rzp_test_key:" + KEY_SECRET).getBytes());
        if (!expected.equals(auth)) {
            respond(ex, 401, "{\"error\":{\"description\":\"bad key\"}}");
            return;
        }
        if (path.equals("/v1/orders")) {
            Matcher m = Pattern.compile("\"amount\"\\s*:\\s*(\\d+)").matcher(body);
            long amount = m.find() ? Long.parseLong(m.group(1)) : -1;
            String id = "order_T" + IDS.incrementAndGet() + "abcdef";
            ORDERS.put(id, amount);
            respond(ex, 200, "{\"id\":\"" + id + "\",\"amount\":" + amount + ",\"currency\":\"INR\",\"status\":\"created\"}");
            return;
        }
        if (path.equals("/v1/payments/qr_codes")) {
            Matcher m = Pattern.compile("\"payment_amount\"\\s*:\\s*(\\d+)").matcher(body);
            long amount = m.find() ? Long.parseLong(m.group(1)) : -1;
            String id = "qr_T" + IDS.incrementAndGet() + "abcdef";
            QRS.put(id, new Object[] {amount, new java.util.ArrayList<String>(), false});
            respond(ex, 200, "{\"id\":\"" + id + "\",\"image_content\":\"upi://pay?pa=rzp.qr@icici&am=" + amount / 100
                    + "&tr=" + id + "\",\"image_url\":\"https://rzp.io/i/x\",\"status\":\"active\"}");
            return;
        }
        Matcher qr = Pattern.compile("^/v1/payments/qr_codes/(qr_[A-Za-z0-9]+)/(payments|close)$").matcher(path);
        if (qr.matches() && QRS.containsKey(qr.group(1))) {
            Object[] q = QRS.get(qr.group(1));
            if (qr.group(2).equals("close")) {
                q[2] = true;
                respond(ex, 200, "{\"id\":\"" + qr.group(1) + "\",\"status\":\"closed\"}");
            } else {
                @SuppressWarnings("unchecked")
                java.util.List<String> items = (java.util.List<String>) q[1];
                respond(ex, 200, "{\"count\":" + items.size() + ",\"items\":[" + String.join(",", items) + "]}");
            }
            return;
        }
        Matcher pay = Pattern.compile("^/v1/payments/(pay_[A-Za-z0-9]+)(/capture)?$").matcher(path);
        if (pay.matches() && PAYMENTS.containsKey(pay.group(1))) {
            Object[] p = PAYMENTS.get(pay.group(1));
            if (pay.group(2) != null) {
                p[2] = "captured";
            }
            respond(ex, 200, "{\"id\":\"" + pay.group(1) + "\",\"order_id\":\"" + p[0] + "\",\"amount\":" + p[1]
                    + ",\"currency\":\"INR\",\"status\":\"" + p[2] + "\",\"method\":\"card\"}");
            return;
        }
        respond(ex, 404, "{}");
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    static String hmac(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private String studio;
    private String studioId;

    @BeforeEach
    void perEventStudio() throws Exception {
        jdbc.update("DELETE FROM event_payments");
        mvc.perform(put("/api/admin/billing/settings").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upiId\":\"mcreatik@okicici\",\"payeeName\":\"McreatiK\",\"perEventPrice\":2500,\"extraEventPrice\":999,\"monthlyPrice\":5000}"))
                .andExpect(status().isOk());
        String email = "online-" + UUID.randomUUID().toString().substring(0, 8) + "@client.test";
        studioId = JsonPath.read(mvc.perform(post("/api/admin/studios").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Pixel\",\"email\":\"" + email + "\",\"password\":\"temporary-123\",\"plan\":\"PER_EVENT\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        studio = login(email);
    }

    private String login(String email) throws Exception {
        int n = IPS.incrementAndGet();
        return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"temporary-123\"}")
                        .with(r -> {
                            r.setRemoteAddr("10.77.0." + n);
                            return r;
                        }))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
    }

    private String start(String token, String body) throws Exception {
        return mvc.perform(post("/api/admin/billing/online-orders").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    /** The stub records a UPI payment to the QR, as if the studio paid in GPay. */
    @SuppressWarnings("unchecked")
    static void payQr(String qrId, String payId, long paise, String status) {
        ((java.util.List<String>) QRS.get(qrId)[1]).add("{\"id\":\"" + payId + "\",\"amount\":" + paise
                + ",\"currency\":\"INR\",\"status\":\"" + status + "\",\"method\":\"upi\"}");
    }

    private String startQr(String token, int events) throws Exception {
        return mvc.perform(post("/api/admin/billing/upi-qr").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"events\":" + events + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private String qrStatus(String token, String paymentId) throws Exception {
        return JsonPath.read(mvc.perform(get("/api/admin/billing/online-orders/" + paymentId).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.status");
    }

    private String qrIdOf(String paymentId) {
        return jdbc.queryForObject("SELECT razorpay_qr_id FROM event_payments WHERE id = ?::uuid", String.class, paymentId);
    }

    @Test
    void aUpiQrIsConfirmedAutomaticallyOnceWithoutATransactionId() throws Exception {
        String res = startQr(studio, 2);
        String paymentId = JsonPath.read(res, "$.paymentId");
        assertThat((String) JsonPath.read(res, "$.upiLink")).startsWith("upi://pay");
        assertThat((Integer) JsonPath.read(res, "$.amount")).isEqualTo(5000);
        String qrId = qrIdOf(paymentId);
        assertThat(QRS.get(qrId)[0]).isEqualTo(500000L); // the server's price, in paise

        assertThat(qrStatus(studio, paymentId)).isEqualTo("WAITING");
        assertThat(credits()).isZero();
        payQr(qrId, "pay_QR1abcdefgh", 500000, "captured");
        assertThat(qrStatus(studio, paymentId)).isEqualTo("PAID");
        assertThat(credits()).isEqualTo(2);
        assertThat(qrStatus(studio, paymentId)).isEqualTo("PAID");
        String body = "{\"event\":\"qr_code.credited\",\"payload\":{\"qr_code\":{\"entity\":{\"id\":\"" + qrId + "\"}}}}";
        mvc.perform(post("/api/public/razorpay/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Razorpay-Signature", hmac(WEBHOOK_SECRET, body))).andExpect(status().isOk());
        assertThat(credits()).isEqualTo(2); // never twice
        assertThat(jdbc.queryForObject("SELECT method || '/' || status FROM event_payments WHERE id = ?::uuid", String.class, paymentId))
                .isEqualTo("UPI_AUTO/APPROVED");
    }

    @Test
    void theQrWebhookCreditsAStudioWhoClosedTheDialogAndIgnoresForgeriesAndShortPayments() throws Exception {
        String paymentId = JsonPath.read(startQr(studio, 1), "$.paymentId");
        String qrId = qrIdOf(paymentId);
        payQr(qrId, "pay_QR2abcdefgh", 100, "captured"); // wrong amount
        String body = "{\"event\":\"qr_code.credited\",\"payload\":{\"qr_code\":{\"entity\":{\"id\":\"" + qrId + "\"}}}}";
        mvc.perform(post("/api/public/razorpay/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Razorpay-Signature", "forged")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/public/razorpay/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Razorpay-Signature", hmac(WEBHOOK_SECRET, body))).andExpect(status().isOk());
        assertThat(credits()).isZero();
        payQr(qrId, "pay_QR3abcdefgh", 250000, "captured");
        mvc.perform(post("/api/public/razorpay/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Razorpay-Signature", hmac(WEBHOOK_SECRET, body))).andExpect(status().isOk());
        assertThat(credits()).isEqualTo(1);
    }

    @Test
    void closingTheQrStopsItAndAnotherStudioCantSeeIt() throws Exception {
        String paymentId = JsonPath.read(startQr(studio, 1), "$.paymentId");
        String other = "other-" + UUID.randomUUID().toString().substring(0, 8) + "@client.test";
        mvc.perform(post("/api/admin/studios").header("Authorization", bearer(adminToken())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Other\",\"email\":\"" + other + "\",\"password\":\"temporary-123\",\"plan\":\"PER_EVENT\"}"))
                .andExpect(status().isCreated());
        String otherToken = login(other);
        mvc.perform(get("/api/admin/billing/online-orders/" + paymentId).header("Authorization", bearer(otherToken)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/admin/billing/online-orders/" + paymentId).header("Authorization", bearer(studio)))
                .andExpect(status().isNoContent());
        assertThat(QRS.get(qrIdOf(paymentId))[2]).isEqualTo(true);
        assertThat(credits()).isZero();
        jdbc.update("UPDATE event_payments SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid", paymentId);
        assertThat(qrStatus(studio, paymentId)).isEqualTo("EXPIRED");
    }

    private int credits() {
        return jdbc.queryForObject("SELECT event_credits FROM admin_users WHERE id = ?::uuid", Integer.class, studioId);
    }

    private org.springframework.test.web.servlet.ResultActions confirm(String token, String paymentId, String payId,
                                                                     String signature) throws Exception {
        return mvc.perform(post("/api/admin/billing/online-orders/" + paymentId + "/confirm").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"razorpayPaymentId\":\"" + payId + "\",\"razorpaySignature\":\"" + signature + "\"}"));
    }

    @Test
    void payingOnlineAddsTheEventsOnceAtTheServersPrice() throws Exception {
        mvc.perform(get("/api/admin/billing").header("Authorization", bearer(studio)))
                .andExpect(jsonPath("$.onlinePayments").value(true));
        // the browser can't choose the price: extra fields are ignored, the server prices 2 x ₹2,500
        String order = start(studio, "{\"events\":2,\"amount\":1}");
        String orderId = JsonPath.read(order, "$.razorpayOrderId");
        String paymentId = JsonPath.read(order, "$.paymentId");
        assertThat(JsonPath.<Integer>read(order, "$.amountPaise")).isEqualTo(500_000);
        assertThat(JsonPath.<String>read(order, "$.keyId")).isEqualTo("rzp_test_key");
        assertThat(ORDERS.get(orderId)).isEqualTo(500_000L);
        assertThat(credits()).isZero();

        PAYMENTS.put("pay_OK1abcdefgh", new Object[] {orderId, 500_000L, "captured"});
        String sig = hmac(KEY_SECRET, orderId + "|pay_OK1abcdefgh");
        confirm(studio, paymentId, "pay_OK1abcdefgh", sig).andExpect(status().isOk()).andExpect(jsonPath("$.events").value(2));
        assertThat(credits()).isEqualTo(2);
        confirm(studio, paymentId, "pay_OK1abcdefgh", sig).andExpect(status().isOk()); // repeat: no double credit
        assertThat(credits()).isEqualTo(2);

        // shows as an online payment, already approved, never in "payments to check"
        mvc.perform(get("/api/admin/billing/payments?status=PENDING").header("Authorization", bearer(adminToken())))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/admin/billing").header("Authorization", bearer(studio)))
                .andExpect(jsonPath("$.payments[0].method").value("RAZORPAY"))
                .andExpect(jsonPath("$.payments[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.payments[0].razorpayPaymentId").value("pay_OK1abcdefgh"));

        // and the studio can create its 2 events
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/admin/events").header("Authorization", bearer(studio)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Wedding " + i + "\",\"eventDate\":\"2026-12-01\"}")).andExpect(status().isCreated());
        }
    }

    @Test
    void aForgedOrMismatchedPaymentAddsNothing() throws Exception {
        String order = start(studio, "{\"events\":1}");
        String orderId = JsonPath.read(order, "$.razorpayOrderId");
        String paymentId = JsonPath.read(order, "$.paymentId");
        PAYMENTS.put("pay_LOW1abcdefg", new Object[] {orderId, 100L, "captured"}); // paid ₹1, not ₹2,500
        confirm(studio, paymentId, "pay_LOW1abcdefg", "deadbeef").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        confirm(studio, paymentId, "pay_LOW1abcdefg", hmac(KEY_SECRET, orderId + "|pay_LOW1abcdefg")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_MISMATCH"));
        PAYMENTS.put("pay_FAIL1abcdef", new Object[] {orderId, 250_000L, "failed"});
        confirm(studio, paymentId, "pay_FAIL1abcdef", hmac(KEY_SECRET, orderId + "|pay_FAIL1abcdef")).andExpect(status().isPaymentRequired());
        assertThat(credits()).isZero();
    }

    @Test
    void anAuthorizedPaymentIsCaptured() throws Exception {
        String order = start(studio, "{\"events\":1}");
        String orderId = JsonPath.read(order, "$.razorpayOrderId");
        PAYMENTS.put("pay_AUTH1abcdef", new Object[] {orderId, 250_000L, "authorized"});
        confirm(studio, JsonPath.read(order, "$.paymentId"), "pay_AUTH1abcdef", hmac(KEY_SECRET, orderId + "|pay_AUTH1abcdef"))
                .andExpect(status().isOk());
        assertThat(credits()).isEqualTo(1);
    }

    @Test
    void anotherStudioCantConfirmThisOrder() throws Exception {
        String order = start(studio, "{\"events\":1}");
        String orderId = JsonPath.read(order, "$.razorpayOrderId");
        PAYMENTS.put("pay_X1abcdefghi", new Object[] {orderId, 250_000L, "captured"});
        String email = "other-" + UUID.randomUUID().toString().substring(0, 8) + "@client.test";
        mvc.perform(post("/api/admin/studios").header("Authorization", bearer(adminToken())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Other\",\"email\":\"" + email + "\",\"password\":\"temporary-123\",\"plan\":\"PER_EVENT\"}"))
                .andExpect(status().isCreated());
        confirm(login(email), JsonPath.read(order, "$.paymentId"), "pay_X1abcdefghi", hmac(KEY_SECRET, orderId + "|pay_X1abcdefghi"))
                .andExpect(status().isNotFound());
        assertThat(credits()).isZero();
    }

    @Test
    void theWebhookCreditsAStudioWhoClosedTheTabAndIgnoresForgeries() throws Exception {
        String order = start(studio, "{\"events\":3}");
        String orderId = JsonPath.read(order, "$.razorpayOrderId");
        PAYMENTS.put("pay_WH1abcdefgh", new Object[] {orderId, 750_000L, "captured"});
        String body = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_WH1abcdefgh\",\"order_id\":\""
                + orderId + "\",\"amount\":750000,\"currency\":\"INR\",\"status\":\"captured\"}}}}";
        mvc.perform(post("/api/public/razorpay/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Razorpay-Signature", "forged")).andExpect(status().isUnauthorized());
        assertThat(credits()).isZero();
        mvc.perform(post("/api/public/razorpay/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Razorpay-Signature", hmac(WEBHOOK_SECRET, body))).andExpect(status().isOk());
        assertThat(credits()).isEqualTo(3);
        // Razorpay also sends order.paid, and retries: still 3
        String orderPaid = body.replace("payment.captured", "order.paid");
        mvc.perform(post("/api/public/razorpay/webhook").contentType(MediaType.APPLICATION_JSON).content(orderPaid)
                .header("X-Razorpay-Signature", hmac(WEBHOOK_SECRET, orderPaid))).andExpect(status().isOk());
        assertThat(credits()).isEqualTo(3);
        // a payment of the main website's store on the same Razorpay account is ignored
        String other = body.replace(orderId, "order_STORE123456");
        mvc.perform(post("/api/public/razorpay/webhook").contentType(MediaType.APPLICATION_JSON).content(other)
                .header("X-Razorpay-Signature", hmac(WEBHOOK_SECRET, other))).andExpect(status().isOk());
        assertThat(credits()).isEqualTo(3);
    }

    @Test
    void unlimitedPlansDontNeedToPay() throws Exception {
        mvc.perform(patch("/api/admin/billing/studios/" + studioId).header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"UNLIMITED\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/admin/billing/online-orders").header("Authorization", bearer(studio))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"events\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_NEEDED"));
        mvc.perform(post("/api/admin/billing/online-orders").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"events\":0}"))
                .andExpect(status().isBadRequest());
    }
}
