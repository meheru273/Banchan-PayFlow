package com.payflow.payment.api.dto;

import com.payflow.payment.domain.Wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WalletResponse(
        UUID id,
        String owner,
        String currency,
        BigDecimal balance,
        Instant createdAt) {

    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
                wallet.getId(),
                wallet.getOwner(),
                wallet.getCurrency(),
                wallet.getBalance(),
                wallet.getCreatedAt());
    }
}
