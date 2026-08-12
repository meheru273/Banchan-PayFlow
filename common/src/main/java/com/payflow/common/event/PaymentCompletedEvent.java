package com.payflow.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by payment-service when a payment completes; consumed by
 * ledger-worker to post the balanced DEBIT/CREDIT pair (Tier 2).
 */
public record PaymentCompletedEvent(
    UUID paymentId,
    UUID walletId,
    BigDecimal amount,
    String currency,
    Instant occurredAt) {
}
