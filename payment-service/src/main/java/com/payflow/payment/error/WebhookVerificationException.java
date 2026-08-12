package com.payflow.payment.error;

/** Webhook failed HMAC or timestamp verification (maps to 401). */
public class WebhookVerificationException extends RuntimeException {

    public WebhookVerificationException(String message) {
        super(message);
    }
}
