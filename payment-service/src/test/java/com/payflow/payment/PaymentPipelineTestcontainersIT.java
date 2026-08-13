package com.payflow.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.common.event.PaymentCompletedEvent;
import com.payflow.common.ledger.LedgerPostingService;
import com.payflow.common.messaging.Messaging;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real thing: Flyway migrations against real PostgreSQL and the event
 * published through real RabbitMQ. The test plays the worker's role by
 * receiving the event off the queue and invoking the same posting service the
 * worker uses, then asserts the money moved. Skipped automatically on
 * machines without Docker; runs in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"payflow.provider.delay=200ms"})
class PaymentPipelineTestcontainersIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void paymentFlowsThroughRealPostgresAndRealRabbit() throws Exception {
        // Demo data was seeded through Flyway-migrated real Postgres at boot
        ResponseEntity<String> wallets = rest.getForEntity("/api/v1/wallets", String.class);
        JsonNode list = objectMapper.readTree(wallets.getBody());
        assertThat(list.size()).isGreaterThanOrEqualTo(2);
        String walletId = list.get(0).get("id").asText();
        BigDecimal before = new BigDecimal(list.get(0).get("balance").asText());

        // Create a payment; the simulated provider webhook publishes the event
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "tc-" + UUID.randomUUID());
        ResponseEntity<String> created = rest.postForEntity("/api/v1/payments",
                new HttpEntity<>("{\"walletId\":\"" + walletId + "\",\"amount\":6.60,\"currency\":\"GBP\",\"cardRef\":\"tok_visa_4242\"}", headers),
                String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        UUID paymentId = UUID.fromString(objectMapper.readTree(created.getBody()).get("id").asText());

        // The event arrives on the real queue
        Object received = rabbitTemplate.receiveAndConvert(Messaging.LEDGER_QUEUE, 15_000);
        assertThat(received).isInstanceOf(PaymentCompletedEvent.class);
        PaymentCompletedEvent event = (PaymentCompletedEvent) received;
        assertThat(event.paymentId()).isEqualTo(paymentId);
        assertThat(event.amount()).isEqualByComparingTo(new BigDecimal("6.60"));

        // Play the worker's part with the identical posting service
        ledgerPostingService.postForPayment(event.paymentId());

        JsonNode payment = objectMapper.readTree(
                rest.getForEntity("/api/v1/payments/" + paymentId, String.class).getBody());
        assertThat(payment.get("status").asText()).isEqualTo("COMPLETED");

        JsonNode wallet = objectMapper.readTree(
                rest.getForEntity("/api/v1/wallets/" + walletId, String.class).getBody());
        assertThat(new BigDecimal(wallet.get("balance").asText()))
                .isEqualByComparingTo(before.add(new BigDecimal("6.60")));
    }
}
