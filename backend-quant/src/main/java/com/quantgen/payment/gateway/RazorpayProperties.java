package com.quantgen.payment.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Razorpay test-mode credentials (PRD 7.9), from QG_RAZORPAY_KEY_ID / _KEY_SECRET / _WEBHOOK_SECRET. The key id is
 * public (Checkout needs it); the two secrets never leave the server and are never logged.
 */
@ConfigurationProperties(prefix = "qg.razorpay")
public record RazorpayProperties(String keyId, String keySecret, String webhookSecret, String baseUrl) {

    public RazorpayProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.razorpay.com/v1" : baseUrl;
    }

    /** Orders can be created and payments verified. */
    public boolean apiConfigured() {
        return notBlank(keyId) && notBlank(keySecret);
    }

    public boolean webhookConfigured() {
        return notBlank(webhookSecret);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    @Override
    public String toString() {
        return "RazorpayProperties[keyId=" + keyId + ", secrets=redacted, baseUrl=" + baseUrl + "]";
    }
}
