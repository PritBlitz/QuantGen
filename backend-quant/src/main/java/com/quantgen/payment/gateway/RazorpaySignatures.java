package com.quantgen.payment.gateway;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Razorpay's HMAC-SHA256 signatures, compared in constant time (PRD 7.9). */
public final class RazorpaySignatures {

    private RazorpaySignatures() {
    }

    /** Checkout's signature: HMAC(order_id + "|" + payment_id, key secret), hex. */
    public static boolean validPayment(String orderId, String paymentId, String signature, String keySecret) {
        return matches(hmacHex(orderId + "|" + paymentId, keySecret), signature);
    }

    /** A webhook's X-Razorpay-Signature: HMAC(raw body, webhook secret), hex. */
    public static boolean validWebhook(byte[] rawBody, String signature, String webhookSecret) {
        return matches(hmacHex(rawBody, webhookSecret), signature);
    }

    public static String hmacHex(String data, String secret) {
        return hmacHex(data.getBytes(StandardCharsets.UTF_8), secret);
    }

    public static String hmacHex(byte[] data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean matches(String expectedHex, String given) {
        if (given == null) {
            return false;
        }
        return MessageDigest.isEqual(expectedHex.getBytes(StandardCharsets.US_ASCII),
                given.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }
}
