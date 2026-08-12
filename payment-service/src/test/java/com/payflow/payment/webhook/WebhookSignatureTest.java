package com.payflow.payment.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {

    private static final String SECRET = "test-webhook-secret";
    private static final String BODY = "{\"paymentId\":\"abc\",\"status\":\"succeeded\"}";

    @Test
    void signatureVerifiesForSameSecretTimestampAndBody() {
        String signature = WebhookSignature.sign(SECRET, 1_700_000_000L, BODY);
        assertThat(WebhookSignature.matches(SECRET, 1_700_000_000L, BODY, signature)).isTrue();
    }

    @Test
    void tamperedBodyFailsVerification() {
        String signature = WebhookSignature.sign(SECRET, 1_700_000_000L, BODY);
        assertThat(WebhookSignature.matches(SECRET, 1_700_000_000L, BODY.replace("succeeded", "failed"), signature))
                .isFalse();
    }

    @Test
    void differentTimestampFailsVerification() {
        String signature = WebhookSignature.sign(SECRET, 1_700_000_000L, BODY);
        assertThat(WebhookSignature.matches(SECRET, 1_700_000_001L, BODY, signature)).isFalse();
    }

    @Test
    void differentSecretFailsVerification() {
        String signature = WebhookSignature.sign(SECRET, 1_700_000_000L, BODY);
        assertThat(WebhookSignature.matches("other-secret", 1_700_000_000L, BODY, signature)).isFalse();
    }

    @Test
    void malformedHexIsRejectedNotThrown() {
        assertThat(WebhookSignature.matches(SECRET, 1_700_000_000L, BODY, "not-hex-at-all")).isFalse();
    }
}
