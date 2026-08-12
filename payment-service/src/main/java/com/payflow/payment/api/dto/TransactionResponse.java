package com.payflow.payment.api.dto;

import com.payflow.common.domain.LedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        UUID paymentId,
        String direction,
        BigDecimal amount,
        Instant createdAt) {

    public static TransactionResponse from(LedgerEntry entry) {
        return new TransactionResponse(
                entry.getId(),
                entry.getPaymentId(),
                entry.getDirection().name(),
                entry.getAmount(),
                entry.getCreatedAt());
    }
}
