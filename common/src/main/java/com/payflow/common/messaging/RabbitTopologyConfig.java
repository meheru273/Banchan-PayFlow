package com.payflow.common.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declared identically by both apps (declaration is idempotent, and happens
 * lazily on the first broker connection — so payment-service still boots
 * cleanly when no broker is configured, e.g. the dev profile).
 *
 * A message that exhausts the consumer's retries is rejected without requeue
 * and dead-letters into the DLQ instead of spinning forever.
 */
@Configuration
public class RabbitTopologyConfig {

    @Bean
    TopicExchange eventsExchange() {
        return new TopicExchange(Messaging.EVENTS_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(Messaging.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue ledgerQueue() {
        return QueueBuilder.durable(Messaging.LEDGER_QUEUE)
                .withArgument("x-dead-letter-exchange", Messaging.DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", Messaging.LEDGER_DLQ)
                .build();
    }

    @Bean
    Queue ledgerDeadLetterQueue() {
        return QueueBuilder.durable(Messaging.LEDGER_DLQ).build();
    }

    @Bean
    Binding ledgerBinding(Queue ledgerQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(ledgerQueue).to(eventsExchange).with(Messaging.PAYMENT_COMPLETED_KEY);
    }

    @Bean
    Binding ledgerDlqBinding(Queue ledgerDeadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(ledgerDeadLetterQueue).to(deadLetterExchange).with(Messaging.LEDGER_DLQ);
    }

    @Bean
    MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
