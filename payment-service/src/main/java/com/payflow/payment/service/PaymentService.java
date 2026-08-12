package com.payflow.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.payment.api.dto.CreatePaymentRequest;
import com.payflow.payment.api.dto.PaymentResponse;
import com.payflow.payment.domain.IdempotencyRecord;
import com.payflow.payment.error.IdempotencyConflictException;
import com.payflow.payment.error.ResourceNotFoundException;
import com.payflow.payment.repo.PaymentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Orchestrates payment creation behind the Idempotency-Key contract:
 * same key + same body replays the stored response, same key + different
 * body is a 409, and a concurrent duplicate cannot execute twice.
 */
@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentProcessor paymentProcessor;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public PaymentService(PaymentRepository paymentRepository,
                          PaymentProcessor paymentProcessor,
                          IdempotencyService idempotencyService,
                          ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.paymentProcessor = paymentProcessor;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    public record CreatePaymentResult(int statusCode, String bodyJson, boolean replayed, UUID paymentId) {
    }

    public CreatePaymentResult create(String idempotencyKey, CreatePaymentRequest request) {
        String requestHash = hash(request);
        // Fast path: a sequential replay finds the record without attempting the
        // insert, keeping duplicate-key exceptions (and their ERROR log noise)
        // out of the common case. The claim below still arbitrates true races.
        Optional<IdempotencyRecord> existing = idempotencyService.find(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }
        try {
            idempotencyService.claim(idempotencyKey, requestHash);
        } catch (DataIntegrityViolationException e) {
            return replay(idempotencyKey, requestHash);
        }
        try {
            PaymentResponse response = paymentProcessor.process(request);
            String body = toJson(response);
            idempotencyService.complete(idempotencyKey, HttpStatus.CREATED.value(), body);
            return new CreatePaymentResult(HttpStatus.CREATED.value(), body, false, response.id());
        } catch (RuntimeException e) {
            idempotencyService.release(idempotencyKey);
            throw e;
        }
    }

    public PaymentResponse getPayment(UUID id) {
        return paymentRepository.findById(id)
                .map(PaymentResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", id));
    }

    private CreatePaymentResult replay(String key, String requestHash) {
        IdempotencyRecord record = idempotencyService.find(key)
                .orElseThrow(() -> new IdempotencyConflictException(
                        "Concurrent request with Idempotency-Key '" + key + "' — retry shortly"));
        return replay(record, requestHash);
    }

    private CreatePaymentResult replay(IdempotencyRecord record, String requestHash) {
        String key = record.getKey();
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(
                    "Idempotency-Key '" + key + "' was already used with a different request body");
        }
        if (record.getResponseBody() == null) {
            throw new IdempotencyConflictException(
                    "The request with Idempotency-Key '" + key + "' is still being processed — retry shortly");
        }
        return new CreatePaymentResult(record.getStatusCode(), record.getResponseBody(), true, null);
    }

    private String hash(CreatePaymentRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] canonical = objectMapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(digest.digest(canonical));
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException("Could not hash request body", e);
        }
    }

    private String toJson(PaymentResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize payment response", e);
        }
    }
}
