package com.payflow.payment.webhook;

import java.util.UUID;

/** Body of the simulated provider's confirmation webhook. */
public record ProviderWebhookPayload(UUID paymentId, String providerRef, String status) {
}
