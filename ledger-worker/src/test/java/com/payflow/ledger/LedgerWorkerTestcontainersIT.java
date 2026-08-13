package com.payflow.ledger;

import com.payflow.common.domain.Payment;
import com.payflow.common.domain.PaymentStatus;
import com.payflow.common.domain.Wallet;
import com.payflow.common.event.PaymentCompletedEvent;
import com.payflow.common.ledger.LedgerPostingService;
import com.payflow.common.messaging.Messaging;
import com.payflow.common.repo.LedgerEntryRepository;
import com.payflow.common.repo.PaymentRepository;
import com.payflow.common.repo.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The worker consuming from real RabbitMQ into real PostgreSQL, including the
 * poison-message path: a failing event is retried with backoff and then
 * dead-letters into the DLQ instead of spinning forever. Skipped without
 * Docker; runs in CI.
 *
 * Schema note: the worker owns no migrations (payment-service does), so this
 * test lets Hibernate create the schema; the real-schema/Flyway combination is
 * covered by payment-service's Testcontainers IT.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.listener.simple.retry.max-attempts=2",
        "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
        "spring.rabbitmq.listener.simple.retry.max-interval=200ms"
})
class LedgerWorkerTestcontainersIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Test
    void consumesEventAndPostsBalancedLedger() {
        Wallet treasury = walletRepository.save(
                new Wallet(LedgerPostingService.TREASURY_OWNER, "GBP", new BigDecimal("1000.00")));
        Wallet customer = walletRepository.save(new Wallet("Jisoo Kim", "GBP", BigDecimal.ZERO));
        Payment payment = paymentRepository.save(
                new Payment(customer.getId(), new BigDecimal("42.50"), "GBP", null));

        rabbitTemplate.convertAndSend(Messaging.EVENTS_EXCHANGE, Messaging.PAYMENT_COMPLETED_KEY,
                new PaymentCompletedEvent(payment.getId(), customer.getId(),
                        new BigDecimal("42.50"), "GBP", Instant.now()));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Payment reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        });
        assertThat(ledgerEntryRepository.signedSumForPayment(payment.getId())).isZero();
        assertThat(walletRepository.findById(customer.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("42.50"));
        assertThat(walletRepository.findById(treasury.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("957.50"));
    }

    @Test
    void poisonMessageLandsInTheDeadLetterQueue() {
        // An event for a payment that doesn't exist fails every attempt
        rabbitTemplate.convertAndSend(Messaging.EVENTS_EXCHANGE, Messaging.PAYMENT_COMPLETED_KEY,
                new PaymentCompletedEvent(UUID.randomUUID(), UUID.randomUUID(),
                        BigDecimal.ONE, "GBP", Instant.now()));

        Message deadLettered = rabbitTemplate.receive(Messaging.LEDGER_DLQ, 15_000);
        assertThat(deadLettered).isNotNull();
    }
}
