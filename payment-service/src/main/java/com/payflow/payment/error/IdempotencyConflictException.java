package com.payflow.payment.error;

/** Idempotency-Key reuse with a different body, or a first request still in flight (maps to 409). */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
