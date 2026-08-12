package com.payflow.payment.error;

/** Request is well-formed but violates a business rule (maps to 422). */
public class DomainValidationException extends RuntimeException {

    public DomainValidationException(String message) {
        super(message);
    }
}
