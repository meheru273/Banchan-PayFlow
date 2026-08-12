package com.payflow.payment.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * HMAC-SHA256 over "timestamp.body". The timestamp is part of the signed
 * content, so a replayed body can't be re-dated, and verification uses
 * {@link MessageDigest#isEqual} — a constant-time comparison that doesn't
 * leak how many prefix bytes matched.
 */
public final class WebhookSignature {

    public static final String SIGNATURE_HEADER = "X-Webhook-Signature";
    public static final String TIMESTAMP_HEADER = "X-Webhook-Timestamp";

    private WebhookSignature() {
    }

    public static String sign(String secret, long timestampSeconds, String body) {
        return HexFormat.of().formatHex(hmac(secret, timestampSeconds, body));
    }

    public static boolean matches(String secret, long timestampSeconds, String body, String providedHex) {
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(providedHex);
        } catch (RuntimeException e) {
            return false;
        }
        return MessageDigest.isEqual(hmac(secret, timestampSeconds, body), provided);
    }

    private static byte[] hmac(String secret, long timestampSeconds, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal((timestampSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }
}
