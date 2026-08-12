package com.payflow.payment.messaging;

import com.payflow.common.domain.Payment;
import com.payflow.common.event.PaymentCompletedEvent;
import com.payflow.common.messaging.Messaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public PaymentEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishPaymentCompleted(Payment payment) {
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                payment.getId(), payment.getWalletId(), payment.getAmount(),
                payment.getCurrency(), Instant.now());
        rabbitTemplate.convertAndSend(
                Messaging.EVENTS_EXCHANGE, Messaging.PAYMENT_COMPLETED_KEY, event);
        log.info("Published payment.completed for payment {}", payment.getId());
    }
}
