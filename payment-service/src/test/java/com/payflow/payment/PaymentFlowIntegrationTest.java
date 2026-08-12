package com.payflow.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Tier 1 flow against the dev (H2 PostgreSQL-mode) profile:
 * Flyway migration, seeding, payment creation, idempotent replay, key-reuse
 * conflict, and the double-entry ledger visible through the wallet API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentFlowIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    private HttpHeaders jsonHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    private JsonNode parse(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    private String firstWalletId() throws Exception {
        ResponseEntity<String> wallets = rest.getForEntity("/api/v1/wallets", String.class);
        assertThat(wallets.getStatusCode().value()).isEqualTo(200);
        JsonNode list = parse(wallets.getBody());
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isGreaterThanOrEqualTo(2);
        return list.get(0).get("id").asText();
    }

    @Test
    void healthEndpointIsUp() {
        ResponseEntity<String> response = rest.getForEntity("/health", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("UP");
    }

    @Test
    void rootLeadsToSwaggerUi() {
        // 302 when the client doesn't follow redirects, 200 when it does.
        ResponseEntity<String> response = rest.getForEntity("/", String.class);
        assertThat(response.getStatusCode().value()).isIn(200, 302);
        if (response.getStatusCode().value() == 302) {
            assertThat(response.getHeaders().getLocation().getPath()).isEqualTo("/swagger-ui.html");
        }
    }

    @Test
    void fullPaymentFlowWithIdempotency() throws Exception {
        String walletId = firstWalletId();
        String key = "it-" + UUID.randomUUID();
        String body = """
                {"walletId":"%s","amount":12.34,"currency":"GBP","cardRef":"tok_visa_4242"}
                """.formatted(walletId);

        BigDecimal balanceBefore = new BigDecimal(
                parse(rest.getForEntity("/api/v1/wallets/" + walletId, String.class).getBody())
                        .get("balance").asText());

        // 1. Fresh create → 201 COMPLETED
        ResponseEntity<String> created = rest.postForEntity(
                "/api/v1/payments", new HttpEntity<>(body, jsonHeaders(key)), String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("false");
        JsonNode payment = parse(created.getBody());
        assertThat(payment.get("status").asText()).isEqualTo("COMPLETED");
        String paymentId = payment.get("id").asText();

        // 2. Same key + same body → replayed, byte-identical response
        ResponseEntity<String> replayed = rest.postForEntity(
                "/api/v1/payments", new HttpEntity<>(body, jsonHeaders(key)), String.class);
        assertThat(replayed.getStatusCode().value()).isEqualTo(201);
        assertThat(replayed.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replayed.getBody()).isEqualTo(created.getBody());

        // 3. Same key + different body → 409 problem detail
        String differentBody = """
                {"walletId":"%s","amount":99.99,"currency":"GBP"}
                """.formatted(walletId);
        ResponseEntity<String> conflict = rest.postForEntity(
                "/api/v1/payments", new HttpEntity<>(differentBody, jsonHeaders(key)), String.class);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(parse(conflict.getBody()).get("title").asText()).isEqualTo("Idempotency Conflict");

        // 4. Wallet balance moved exactly once
        JsonNode wallet = parse(rest.getForEntity("/api/v1/wallets/" + walletId, String.class).getBody());
        assertThat(new BigDecimal(wallet.get("balance").asText()))
                .isEqualByComparingTo(balanceBefore.add(new BigDecimal("12.34")));

        // 5. Ledger shows a CREDIT for this payment on the wallet
        JsonNode transactions = parse(
                rest.getForEntity("/api/v1/wallets/" + walletId + "/transactions", String.class).getBody());
        boolean creditFound = false;
        for (JsonNode t : transactions) {
            if (t.get("paymentId").asText().equals(paymentId)) {
                assertThat(t.get("direction").asText()).isEqualTo("CREDIT");
                assertThat(new BigDecimal(t.get("amount").asText()))
                        .isEqualByComparingTo(new BigDecimal("12.34"));
                creditFound = true;
            }
        }
        assertThat(creditFound).isTrue();

        // 6. GET payment by id
        JsonNode fetched = parse(rest.getForEntity("/api/v1/payments/" + paymentId, String.class).getBody());
        assertThat(fetched.get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void missingIdempotencyKeyIsBadRequest() throws Exception {
        String walletId = firstWalletId();
        String body = """
                {"walletId":"%s","amount":5.00,"currency":"GBP"}
                """.formatted(walletId);
        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/payments", new HttpEntity<>(body, jsonHeaders(null)), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void validationErrorsReturnProblemDetailWithFieldErrors() throws Exception {
        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/payments",
                new HttpEntity<>("{\"amount\":-5,\"currency\":\"gbp\"}", jsonHeaders("k-" + UUID.randomUUID())),
                String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        JsonNode problem = parse(response.getBody());
        assertThat(problem.get("errors").has("walletId")).isTrue();
        assertThat(problem.get("errors").has("currency")).isTrue();
    }

    @Test
    void unknownPaymentIsNotFoundProblem() throws Exception {
        ResponseEntity<String> response = rest.getForEntity(
                "/api/v1/payments/" + UUID.randomUUID(), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(parse(response.getBody()).get("title").asText()).isEqualTo("Not Found");
    }

    @Test
    void demoResetReseedsWallets() throws Exception {
        ResponseEntity<String> response = rest.postForEntity("/api/v1/demo/reset", null, String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode wallets = parse(response.getBody());
        assertThat(wallets.size()).isEqualTo(2);
        // Reseeded wallets carry the seeded payment history
        String id = wallets.get(0).get("id").asText();
        JsonNode transactions = parse(
                rest.getForEntity("/api/v1/wallets/" + id + "/transactions", String.class).getBody());
        assertThat(transactions.size()).isGreaterThanOrEqualTo(1);
    }
}
