package com.mcreatik.gallery.billing;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.mcreatik.gallery.common.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The two Razorpay API calls we need (create an order, read a payment), plus signature checks. No SDK needed. */
@Component
public class RazorpayClient {

    public record Order(String id, long amountPaise, String currency, String status) {
    }

    public record PaymentInfo(String id, String orderId, long amountPaise, String currency, String status, String method) {
    }

    private final RazorpayProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public RazorpayClient(RazorpayProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    public Order createOrder(long amountPaise, String receipt, Map<String, String> notes) {
        String body = json.writeValueAsString(Map.of("amount", amountPaise, "currency", "INR", "receipt", receipt,
                "notes", notes));
        JsonNode n = send(request("/v1/orders").POST(HttpRequest.BodyPublishers.ofString(body)).build());
        return new Order(n.path("id").asString(), n.path("amount").asLong(), n.path("currency").asString(),
                n.path("status").asString());
    }

    public PaymentInfo payment(String paymentId) {
        if (!paymentId.matches("^pay_[A-Za-z0-9]{6,40}$")) {
            throw ApiException.badRequest("INVALID_PAYMENT", "Unknown payment");
        }
        JsonNode n = send(request("/v1/payments/" + paymentId).GET().build());
        return info(n);
    }

    /** Razorpay captures automatically by default; this covers accounts set to manual capture. */
    public PaymentInfo capture(String paymentId, long amountPaise) {
        String body = json.writeValueAsString(Map.of("amount", amountPaise, "currency", "INR"));
        return info(send(request("/v1/payments/" + paymentId + "/capture").POST(HttpRequest.BodyPublishers.ofString(body)).build()));
    }

    private PaymentInfo info(JsonNode n) {
        return new PaymentInfo(n.path("id").asString(), n.path("order_id").asString(null), n.path("amount").asLong(),
                n.path("currency").asString(), n.path("status").asString(), n.path("method").asString(null));
    }

    private HttpRequest.Builder request(String path) {
        String auth = Base64.getEncoder().encodeToString((props.keyId() + ":" + props.keySecret()).getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create(props.apiBaseUrl() + path)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Basic " + auth).header("Content-Type", "application/json");
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_GATEWAY_ERROR",
                        "The payment service didn't accept the request. Please try again or pay by UPI QR.");
            }
            return json.readTree(r.body());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_GATEWAY_UNAVAILABLE",
                    "The payment service can't be reached right now. Please try again or pay by UPI QR.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_GATEWAY_UNAVAILABLE", "Interrupted");
        }
    }

    /** Checkout's signature: HMAC-SHA256 of "order_id|payment_id" with the key secret. */
    public boolean checkoutSignatureValid(String orderId, String paymentId, String signature) {
        return matches(hmac(props.keySecret(), orderId + "|" + paymentId), signature);
    }

    /** Webhook signature: HMAC-SHA256 of the raw request body with the webhook secret. */
    public boolean webhookSignatureValid(byte[] rawBody, String signature) {
        return matches(hmac(props.webhookSecret(), rawBody), signature);
    }

    static String hmac(String secret, String data) {
        return hmac(secret, data.getBytes(StandardCharsets.UTF_8));
    }

    static String hmac(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean matches(String expected, String given) {
        return given != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                given.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }
}
