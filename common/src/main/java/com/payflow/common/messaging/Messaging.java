package com.payflow.common.messaging;

/** Names shared by publisher (payment-service) and consumer (ledger-worker). */
public final class Messaging {

    public static final String EVENTS_EXCHANGE = "payflow.events";
    public static final String PAYMENT_COMPLETED_KEY = "payment.completed";
    public static final String LEDGER_QUEUE = "ledger.payment.completed";
    public static final String DEAD_LETTER_EXCHANGE = "payflow.dlx";
    public static final String LEDGER_DLQ = "ledger.payment.completed.dlq";

    private Messaging() {
    }
}
