package com.payflow.payment.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.common.domain.Payment;
import com.payflow.common.ledger.LedgerPostingService;
import com.payflow.common.repo.PaymentRepository;
import com.payflow.payment.error.DomainValidationException;
import com.payflow.payment.error.ResourceNotFoundException;
import com.payflow.payment.error.WebhookVerificationException;
import com.payflow.payment.messaging.PaymentEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class PaymentWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);
    static final String DEV_FALLBACK_SECRET = "payflow-dev-only-insecure-webhook-secret";

    private final ObjectMapper objectMapper;
    private final PaymentRepository paymentRepository;
    private final LedgerPostingService ledgerPostingService;
    private final PaymentEventPublisher eventPublisher;
    private final String secret;
    private final Duration tolerance;
    private final boolean messagingEnabled;

    public PaymentWebhookService(ObjectMapper objectMapper,
                                 PaymentRepository paymentRepository,
                                 LedgerPostingService ledgerPostingService,
                                 PaymentEventPublisher eventPublisher,
                                 @Value("${payflow.webhook.secret:}") String secret,
                                 @Value("${payflow.webhook.tolerance:5m}") Duration tolerance,
                                 @Value("${payflow.messaging.enabled:true}") boolean messagingEnabled) {
        this.objectMapper = objectMapper;
        this.paymentRepository = paymentRepository;
        this.ledgerPostingService = ledgerPostingService;
        this.eventPublisher = eventPublisher;
        if (secret == null || secret.isBlank()) {
            log.warn("WEBHOOK_HMAC_SECRET is not set — using an insecure development secret. "
                    + "Set it in any real environment.");
            secret = DEV_FALLBACK_SECRET;
        }
        this.secret = secret;
        this.tolerance = tolerance;
        this.messagingEnabled = messagingEnabled;
    }

    public String secret() {
        return secret;
    }

    public void handle(String signature, long timestampSeconds, String rawBody) {
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - timestampSeconds) > tolerance.toSeconds()) {
            throw new WebhookVerificationException("Webhook timestamp is outside the accepted window");
        }
        if (!WebhookSignature.matches(secret, timestampSeconds, rawBody, signature)) {
            throw new WebhookVerificationException("Webhook signature does not match");
        }

        ProviderWebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, ProviderWebhookPayload.class);
        } catch (JsonProcessingException e) {
            throw new DomainValidationException("Malformed webhook payload");
        }

        Payment payment = paymentRepository.findById(payload.paymentId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment", payload.paymentId()));

        if ("succeeded".equalsIgnoreCase(payload.status())) {
            if (messagingEnabled) {
                eventPublisher.publishPaymentCompleted(payment);
            } else {
                // No broker configured (dev profile): post the ledger in-process.
                ledgerPostingService.postForPayment(payment.getId());
            }
        } else {
            ledgerPostingService.markFailed(payment.getId());
        }
    }
}
