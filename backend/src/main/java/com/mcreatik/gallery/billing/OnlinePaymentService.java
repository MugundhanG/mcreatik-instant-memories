package com.mcreatik.gallery.billing;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.mcreatik.gallery.common.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Buying events online with Razorpay Checkout (card, netbanking, UPI, wallet). The server sets the amount, opens a
 * Razorpay order, and credits the events once: when the studio's browser confirms with Razorpay's signature, or when
 * Razorpay's webhook reports the payment (if the browser was closed). Nothing is credited before Razorpay says paid.
 */
@Service
public class OnlinePaymentService {

    private static final Logger log = Logger.getLogger(OnlinePaymentService.class.getName());

    public record CheckoutOrder(UUID paymentId, String razorpayOrderId, String keyId, long amountPaise, String currency,
                                int events, int amount, String studioName, String email) {
    }

    public record Result(UUID paymentId, int events, int credits) {
    }

    private record Row(UUID id, UUID studioId, int events, int amount, String status, String orderId) {
    }

    private final JdbcTemplate jdbc;
    private final BillingService billing;
    private final RazorpayClient razorpay;
    private final RazorpayProperties props;
    private final ObjectMapper json;

    public OnlinePaymentService(JdbcTemplate jdbc, BillingService billing, RazorpayClient razorpay, RazorpayProperties props,
                                ObjectMapper json) {
        this.jdbc = jdbc;
        this.billing = billing;
        this.razorpay = razorpay;
        this.props = props;
        this.json = json;
    }

    public boolean enabled() {
        return props.enabled();
    }

    /** Opens a Razorpay order for {@code events} at the studio's own price (never an amount from the browser). */
    public CheckoutOrder start(UUID studioId, int events) {
        if (!props.enabled()) {
            throw ApiException.conflict("ONLINE_PAYMENTS_OFF", "Online payment isn't set up yet. Please pay by UPI QR.");
        }
        BillingService.Quote quote = billing.quote(studioId, events);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO event_payments (id, studio_id, events, unit_price, amount, method, status)"
                + " VALUES (?, ?, ?, ?, ?, 'RAZORPAY', 'CREATED')", id, studioId, events, quote.unitPrice(), quote.amount());
        RazorpayClient.Order order = razorpay.createOrder((long) quote.amount() * 100, id.toString(),
                Map.of("studioId", studioId.toString(), "events", Integer.toString(events), "product", "Instant Memories events"));
        jdbc.update("UPDATE event_payments SET razorpay_order_id = ? WHERE id = ?", order.id(), id);
        Map<String, Object> who = jdbc.queryForMap("SELECT display_name, email FROM admin_users WHERE id = ?", studioId);
        return new CheckoutOrder(id, order.id(), props.keyId(), order.amountPaise(), "INR", events, quote.amount(),
                (String) who.get("display_name"), (String) who.get("email"));
    }

    /** The studio's browser came back from Checkout: check Razorpay's signature and the payment, then credit. */
    public Result confirm(UUID studioId, UUID paymentId, String razorpayPaymentId, String signature) {
        Row row = row("id = ?", paymentId);
        if (row == null || !row.studioId().equals(studioId)) {
            throw ApiException.notFound("Payment");
        }
        if (row.orderId() == null || !razorpay.checkoutSignatureValid(row.orderId(), razorpayPaymentId, signature)) {
            throw ApiException.badRequest("INVALID_SIGNATURE", "The payment couldn't be verified");
        }
        return settle(row, razorpayPaymentId);
    }

    /** Razorpay's webhook (payment.captured / order.paid / payment.failed); the signature is checked by the caller. */
    public void webhook(byte[] rawBody) {
        JsonNode root = json.readTree(rawBody);
        String event = root.path("event").asString("");
        JsonNode payment = root.path("payload").path("payment").path("entity");
        String orderId = payment.path("order_id").asString(null);
        String paymentId = payment.path("id").asString(null);
        if (orderId == null || paymentId == null) {
            return; // not one of ours (or an event without a payment)
        }
        Row row = row("razorpay_order_id = ?", orderId);
        if (row == null) {
            return; // another product on the same Razorpay account (e.g. the main website's store)
        }
        switch (event) {
            case "payment.captured", "order.paid" -> settle(row, paymentId);
            case "payment.failed" -> jdbc.update("UPDATE event_payments SET note = ? WHERE id = ? AND status = 'CREATED'",
                    "Payment failed: " + payment.path("error_description").asString("declined"), row.id());
            default -> {
            }
        }
    }

    /**
     * Checks with Razorpay that the payment is real, for this order and the full amount, then credits the events
     * exactly once (a repeat confirm or webhook changes nothing).
     */
    Result settle(Row row, String razorpayPaymentId) {
        if ("APPROVED".equals(row.status())) {
            return new Result(row.id(), row.events(), credits(row.studioId()));
        }
        if (!"CREATED".equals(row.status())) {
            throw ApiException.conflict("PAYMENT_CLOSED", "This payment can't be completed any more");
        }
        RazorpayClient.PaymentInfo p = razorpay.payment(razorpayPaymentId);
        long expected = (long) row.amount() * 100;
        if (!row.orderId().equals(p.orderId()) || p.amountPaise() != expected || !"INR".equals(p.currency())) {
            log.warning("Razorpay payment " + razorpayPaymentId + " does not match order " + row.orderId());
            throw ApiException.badRequest("PAYMENT_MISMATCH", "This payment doesn't match the order");
        }
        if ("authorized".equals(p.status())) {
            p = razorpay.capture(razorpayPaymentId, expected);
        }
        if (!"captured".equals(p.status())) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_NOT_COMPLETE",
                    "The payment hasn't gone through. No money was taken for events; please try again.");
        }
        // One statement: marked paid and credited together, and only by whichever confirm/webhook gets there first.
        int changed = jdbc.update("""
                WITH paid AS (
                    UPDATE event_payments SET status = 'APPROVED', razorpay_payment_id = ?, reviewed_at = now(), note = NULL
                    WHERE id = ? AND status = 'CREATED' RETURNING studio_id, events)
                UPDATE admin_users u SET event_credits = u.event_credits + paid.events FROM paid WHERE u.id = paid.studio_id""",
                razorpayPaymentId, row.id());
        if (changed == 1) {
            log.info("Online payment " + razorpayPaymentId + ": " + row.events() + " event(s) credited");
        }
        return new Result(row.id(), row.events(), credits(row.studioId()));
    }

    private int credits(UUID studioId) {
        return jdbc.queryForObject("SELECT event_credits FROM admin_users WHERE id = ?", Integer.class, studioId);
    }

    private Row row(String where, Object arg) {
        List<Row> rows = jdbc.query("SELECT id, studio_id, events, amount, status, razorpay_order_id FROM event_payments WHERE "
                + where + " AND method = 'RAZORPAY'", (rs, n) -> new Row(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getString(6)), arg);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
