package com.payflow.payment.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CreatePaymentRequest(

        @NotNull
        @Schema(description = "Target wallet id", example = "0d7f9c2e-0000-0000-0000-000000000000")
        UUID walletId,

        @NotNull
        @DecimalMin(value = "0.01")
        @Digits(integer = 17, fraction = 2)
        @Schema(description = "Amount in major units", example = "42.50")
        BigDecimal amount,

        @NotBlank
        @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO 4217 code, e.g. GBP")
        @Schema(description = "ISO 4217 currency code, must match the wallet's currency", example = "GBP")
        String currency,

        @Size(max = 64)
        @Schema(description = "Simulated card token — never a real card number", example = "tok_visa_4242", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String cardRef) {
}
