package com.payflow.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.payment.webhook.WebhookSignature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The webhook endpoint's own authentication: HMAC-SHA256 signature and
 * timestamp freshness. Uses the dev profile's webhook secret.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class WebhookSecurityIntegrationTest {

    private static final String DEV_SECRET = "dev-only-insecure-webhook-secret";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    private String createPendingPayment() throws Exception {
        ResponseEntity<String> wallets = rest.getForEntity("/api/v1/wallets", String.class);
        String walletId = objectMapper.readTree(wallets.getBody()).get(0).get("id").asText();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "wh-" + UUID.randomUUID());
        String body = """
                {"walletId":"%s","amount":9.99,"currency":"GBP"}
                """.formatted(walletId);
        ResponseEntity<String> created = rest.postForEntity(
                "/api/v1/payments", new HttpEntity<>(body, headers), String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        return objectMapper.readTree(created.getBody()).get("id").asText();
    }

    private ResponseEntity<String> postWebhook(String body, long timestamp, String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(WebhookSignature.TIMESTAMP_HEADER, Long.toString(timestamp));
        headers.set(WebhookSignature.SIGNATURE_HEADER, signature);
        return rest.postForEntity("/api/v1/webhooks/provider", new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void tamperedSignatureIsRejected() throws Exception {
        String paymentId = createPendingPayment();
        String body = "{\"paymentId\":\"" + paymentId + "\",\"providerRef\":\"SIM-x\",\"status\":\"succeeded\"}";
        long now = Instant.now().getEpochSecond();

        ResponseEntity<String> response = postWebhook(body, now, "deadbeef" + "00".repeat(28));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        JsonNode problem = objectMapper.readTree(response.getBody());
        assertThat(problem.get("title").asText()).isEqualTo("Webhook Verification Failed");
    }

    @Test
    void staleTimestampIsRejectedEvenWithAValidSignature() throws Exception {
        String paymentId = createPendingPayment();
        String body = "{\"paymentId\":\"" + paymentId + "\",\"providerRef\":\"SIM-x\",\"status\":\"succeeded\"}";
        long stale = Instant.now().getEpochSecond() - 3600;

        ResponseEntity<String> response = postWebhook(body, stale, WebhookSignature.sign(DEV_SECRET, stale, body));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(objectMapper.readTree(response.getBody()).get("detail").asText()).contains("timestamp");
    }

    @Test
    void validSignatureIsAcceptedAndIdempotent() throws Exception {
        String paymentId = createPendingPayment();
        String body = "{\"paymentId\":\"" + paymentId + "\",\"providerRef\":\"SIM-x\",\"status\":\"succeeded\"}";
        long now = Instant.now().getEpochSecond();
        String signature = WebhookSignature.sign(DEV_SECRET, now, body);

        assertThat(postWebhook(body, now, signature).getStatusCode().value()).isEqualTo(200);
        // A second delivery of the same webhook must be a harmless no-op.
        assertThat(postWebhook(body, now, signature).getStatusCode().value()).isEqualTo(200);

        JsonNode payment = objectMapper.readTree(
                rest.getForEntity("/api/v1/payments/" + paymentId, String.class).getBody());
        assertThat(payment.get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void webhookForUnknownPaymentIsNotFound() {
        String body = "{\"paymentId\":\"" + UUID.randomUUID() + "\",\"providerRef\":\"SIM-x\",\"status\":\"succeeded\"}";
        long now = Instant.now().getEpochSecond();

        ResponseEntity<String> response = postWebhook(body, now, WebhookSignature.sign(DEV_SECRET, now, body));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
