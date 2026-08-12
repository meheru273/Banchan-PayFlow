package com.payflow.payment.api.dto;

import com.payflow.common.domain.Payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID walletId,
        BigDecimal amount,
        String currency,
        String status,
        String providerRef,
        Instant createdAt) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getWalletId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus().name(),
                payment.getProviderRef(),
                payment.getCreatedAt());
    }
}
