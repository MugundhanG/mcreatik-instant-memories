package com.mcreatik.gallery.billing;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
 * <p>
 * UPI QR works the same way without Checkout: a single-use Razorpay QR for the exact amount; the studio's dialog asks
 * every few seconds and the {@code qr_code.credited} webhook reports it, so no transaction ID is typed or checked.
 */
@Service
public class OnlinePaymentService {

    private static final Logger log = Logger.getLogger(OnlinePaymentService.class.getName());

    public record CheckoutOrder(UUID paymentId, String razorpayOrderId, String keyId, long amountPaise, String currency,
                                int events, int amount, String studioName, String email) {
    }

    public record Result(UUID paymentId, int events, int credits) {
    }

    /** A UPI QR to show; {@code upiLink} is drawn as the QR (or opened by a UPI app on this phone). */
    public record UpiQrOrder(UUID paymentId, String upiLink, String imageUrl, int events, int amount, Instant expiresAt) {
    }

    /** WAITING (not paid yet), PAID (events added), EXPIRED (QR closed, nothing paid). */
    public record Status(UUID paymentId, String status, int events, int credits) {
    }

    /** How long a UPI QR accepts payment. */
    static final Duration QR_LIFETIME = Duration.ofMinutes(15);

    private record Row(UUID id, UUID studioId, int events, int amount, String status, String method, String orderId,
                       String qrId, Instant expiresAt) {
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

    /** What a purchase is called on the payment screen and in Razorpay. */
    static String describe(BillingService.Quote q) {
        int n = q.events();
        return switch (q.item()) {
            case MAIN_EVENT -> n + " Main event" + (n == 1 ? "" : "s");
            case QUICK_EVENT -> n + " Quick event" + (n == 1 ? "" : "s");
            case UPGRADE -> "Upgrade to Main event";
            case PLAN -> n + " month" + (n == 1 ? "" : "s") + " of " + q.plan().label() + " pack";
            case STORAGE -> n + " GB of gallery storage";
            case CLIENT_SETUP -> n + " client galler" + (n == 1 ? "y" : "ies");
            case EXTEND -> "6 more months for a gallery";
        };
    }

    /** Opens a Razorpay order for a purchase at the studio's own price (never an amount from the browser). */
    public CheckoutOrder start(UUID studioId, BillingService.Purchase purchase) {
        if (!props.enabled()) {
            throw ApiException.conflict("ONLINE_PAYMENTS_OFF", "Online payment isn't set up yet. Please pay by UPI QR.");
        }
        BillingService.Quote quote = billing.quote(studioId, purchase);
        int events = quote.events();
        UUID id = UUID.randomUUID();
        billing.insertOnline(id, studioId, quote, "RAZORPAY", null);
        RazorpayClient.Order order = razorpay.createOrder((long) quote.amount() * 100, id.toString(),
                Map.of("studioId", studioId.toString(), "item", quote.item().name(), "events", Integer.toString(events),
                        "product", "Instant Memories " + describe(quote)));
        jdbc.update("UPDATE event_payments SET razorpay_order_id = ? WHERE id = ?", order.id(), id);
        Map<String, Object> who = jdbc.queryForMap("SELECT display_name, email FROM admin_users WHERE id = ?", studioId);
        return new CheckoutOrder(id, order.id(), props.keyId(), order.amountPaise(), "INR", events, quote.amount(),
                (String) who.get("display_name"), (String) who.get("email"));
    }

    /** A single-use UPI QR for a purchase at the studio's own price; paid in any UPI app, confirmed automatically. */
    public UpiQrOrder startQr(UUID studioId, BillingService.Purchase purchase) {
        if (!props.enabled()) {
            throw ApiException.conflict("ONLINE_PAYMENTS_OFF", "Automatic UPI QR isn't set up yet.");
        }
        BillingService.Quote quote = billing.quote(studioId, purchase);
        int events = quote.events();
        UUID id = UUID.randomUUID();
        Instant expires = Instant.now().plus(QR_LIFETIME);
        billing.insertOnline(id, studioId, quote, "UPI_AUTO", Timestamp.from(expires));
        RazorpayClient.UpiQr qr = razorpay.createUpiQr((long) quote.amount() * 100, expires.getEpochSecond(),
                describe(quote) + " · Instant Memories",
                Map.of("paymentId", id.toString(), "studioId", studioId.toString(), "item", quote.item().name(),
                        "events", Integer.toString(events), "product", "Instant Memories " + describe(quote)));
        jdbc.update("UPDATE event_payments SET razorpay_qr_id = ? WHERE id = ?", qr.id(), id);
        return new UpiQrOrder(id, qr.upiLink(), qr.imageUrl(), events, quote.amount(), expires);
    }

    /** The dialog asks "paid yet?": while waiting, checks the QR with Razorpay and adds the events once paid. */
    public Status status(UUID studioId, UUID paymentId) {
        Row row = owned(studioId, paymentId);
        // Asks Razorpay only while the QR can still be paid (plus a little for a payment made in the last second).
        if ("CREATED".equals(row.status()) && row.qrId() != null
                && (row.expiresAt() == null || Instant.now().isBefore(row.expiresAt().plus(Duration.ofMinutes(2))))) {
            settleQr(row);
            row = row("id = ?", paymentId);
        }
        String status = "APPROVED".equals(row.status()) ? "PAID"
                : row.expiresAt() != null && Instant.now().isAfter(row.expiresAt()) ? "EXPIRED"
                : "CREATED".equals(row.status()) ? "WAITING" : "EXPIRED";
        return new Status(row.id(), status, row.events(), credits(studioId));
    }

    /** The studio closed the dialog or changed the amount: stop the QR. A payment already made still counts. */
    public void cancel(UUID studioId, UUID paymentId) {
        Row row = owned(studioId, paymentId);
        if ("CREATED".equals(row.status()) && row.qrId() != null) {
            razorpay.closeQr(row.qrId());
            settleQr(row); // paid just before closing
        }
    }

    private Row owned(UUID studioId, UUID paymentId) {
        Row row = row("id = ?", paymentId);
        if (row == null || !row.studioId().equals(studioId)) {
            throw ApiException.notFound("Payment");
        }
        return row;
    }

    /**
     * Credits a UPI QR payment if Razorpay lists a captured payment of the full amount on that QR. The list comes
     * from Razorpay's API (never from the browser or the webhook body), so it can be trusted.
     */
    void settleQr(Row row) {
        long expected = (long) row.amount() * 100;
        for (RazorpayClient.PaymentInfo p : razorpay.qrPayments(row.qrId())) {
            if ("captured".equals(p.status()) && p.amountPaise() == expected && "INR".equals(p.currency())) {
                credit(row, p.id());
                return;
            }
        }
    }

    /** The studio's browser came back from Checkout: check Razorpay's signature and the payment, then credit. */
    public Result confirm(UUID studioId, UUID paymentId, String razorpayPaymentId, String signature) {
        Row row = owned(studioId, paymentId);
        if (row.orderId() == null || !razorpay.checkoutSignatureValid(row.orderId(), razorpayPaymentId, signature)) {
            throw ApiException.badRequest("INVALID_SIGNATURE", "The payment couldn't be verified");
        }
        return settle(row, razorpayPaymentId);
    }

    /**
     * Razorpay's webhook (payment.captured / order.paid / payment.failed for Checkout, qr_code.credited for UPI QR);
     * the signature is checked by the caller.
     */
    public void webhook(byte[] rawBody) {
        JsonNode root = json.readTree(rawBody);
        String event = root.path("event").asString("");
        if ("qr_code.credited".equals(event)) {
            String qrId = root.path("payload").path("qr_code").path("entity").path("id").asString(null);
            Row row = qrId == null ? null : row("razorpay_qr_id = ?", qrId);
            if (row != null && "CREATED".equals(row.status())) {
                settleQr(row);
            }
            return; // a QR of another product on the same account: not ours
        }
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
        if (row.orderId() == null || !row.orderId().equals(p.orderId()) || p.amountPaise() != expected || !"INR".equals(p.currency())) {
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
        credit(row, razorpayPaymentId);
        return new Result(row.id(), row.events(), credits(row.studioId()));
    }

    /** Marked paid and applied together (one transaction), only by whichever confirm/poll/webhook gets there first. */
    private void credit(Row row, String razorpayPaymentId) {
        if (billing.markPaid(row.id(), razorpayPaymentId)) {
            log.info("Online payment " + razorpayPaymentId + " applied (payment " + row.id() + ")");
        }
    }

    private int credits(UUID studioId) {
        return billing.credits(studioId);
    }

    private Row row(String where, Object arg) {
        List<Row> rows = jdbc.query("SELECT id, studio_id, events, amount, status, method, razorpay_order_id, razorpay_qr_id,"
                + " expires_at FROM event_payments WHERE " + where + " AND method IN ('RAZORPAY', 'UPI_AUTO')",
                (rs, n) -> {
                    Timestamp exp = rs.getTimestamp(9);
                    return new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getInt(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                            exp == null ? null : exp.toInstant());
                }, arg);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
