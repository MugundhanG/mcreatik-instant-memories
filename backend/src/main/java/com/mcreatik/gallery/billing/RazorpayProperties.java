package com.mcreatik.gallery.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Razorpay keys for online payments (card, netbanking, UPI, wallet). The same account as the main McreatiK
 * website. When any key is missing, online payment is simply off and the UPI QR still works.
 */
@ConfigurationProperties(prefix = "gallery.razorpay")
public record RazorpayProperties(String keyId, String keySecret, String webhookSecret, String apiBaseUrl) {

    public RazorpayProperties {
        apiBaseUrl = apiBaseUrl == null || apiBaseUrl.isBlank() ? "https://api.razorpay.com" : apiBaseUrl.trim();
    }

    public boolean enabled() {
        return notBlank(keyId) && notBlank(keySecret) && notBlank(webhookSecret);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
