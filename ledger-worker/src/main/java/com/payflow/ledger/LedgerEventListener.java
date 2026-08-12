package com.payflow.ledger;

import com.payflow.common.event.PaymentCompletedEvent;
import com.payflow.common.ledger.LedgerPostingService;
import com.payflow.common.messaging.Messaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The queue is the retry boundary: a throw here triggers the configured
 * exponential-backoff retries, and an exhausted (poison) message dead-letters
 * into the DLQ instead of spinning forever. Posting itself is idempotent, so
 * at-least-once delivery cannot double-book a payment.
 */
@Component
public class LedgerEventListener {

    private static final Logger log = LoggerFactory.getLogger(LedgerEventListener.class);

    private final LedgerPostingService ledgerPostingService;

    public LedgerEventListener(LedgerPostingService ledgerPostingService) {
        this.ledgerPostingService = ledgerPostingService;
    }

    @RabbitListener(queues = Messaging.LEDGER_QUEUE)
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        log.info("Received payment.completed for payment {}", event.paymentId());
        ledgerPostingService.postForPayment(event.paymentId());
        log.info("Ledger posted for payment {}", event.paymentId());
    }
}
