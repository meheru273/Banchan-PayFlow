package com.payflow.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.payment.api.dto.CreatePaymentRequest;
import com.payflow.payment.domain.IdempotencyRecord;
import com.payflow.payment.error.IdempotencyConflictException;
import com.payflow.payment.repo.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceIdempotencyTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentProcessor paymentProcessor;

    @Mock
    private IdempotencyService idempotencyService;

    private PaymentService paymentService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CreatePaymentRequest request =
            new CreatePaymentRequest(UUID.randomUUID(), new BigDecimal("42.50"), "GBP", null);

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                paymentRepository, paymentProcessor, idempotencyService, objectMapper);
    }

    private String hashOf(CreatePaymentRequest req) throws Exception {
        byte[] canonical = objectMapper.writeValueAsBytes(req);
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        return java.util.HexFormat.of().formatHex(digest.digest(canonical));
    }

    @Test
    void replaysStoredResponseWhenKeyAndBodyMatch() throws Exception {
        IdempotencyRecord record = new IdempotencyRecord("key-1", hashOf(request));
        record.complete(201, "{\"id\":\"stored\"}");
        when(idempotencyService.find("key-1")).thenReturn(Optional.of(record));

        PaymentService.CreatePaymentResult result = paymentService.create("key-1", request);

        assertThat(result.replayed()).isTrue();
        assertThat(result.statusCode()).isEqualTo(201);
        assertThat(result.bodyJson()).isEqualTo("{\"id\":\"stored\"}");
        verify(paymentProcessor, never()).process(any());
        verify(idempotencyService, never()).claim(anyString(), anyString());
    }

    @Test
    void replaysWhenLosingTheClaimRace() throws Exception {
        // First lookup misses, the claim insert collides, the second lookup replays.
        IdempotencyRecord record = new IdempotencyRecord("key-1", hashOf(request));
        record.complete(201, "{\"id\":\"stored\"}");
        when(idempotencyService.find("key-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(record));
        doThrow(new DataIntegrityViolationException("duplicate"))
                .when(idempotencyService).claim(anyString(), anyString());

        PaymentService.CreatePaymentResult result = paymentService.create("key-1", request);

        assertThat(result.replayed()).isTrue();
        assertThat(result.bodyJson()).isEqualTo("{\"id\":\"stored\"}");
        verify(paymentProcessor, never()).process(any());
    }

    @Test
    void rejectsSameKeyWithDifferentBody() {
        IdempotencyRecord record = new IdempotencyRecord("key-1", "a-different-hash");
        record.complete(201, "{}");
        when(idempotencyService.find("key-1")).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> paymentService.create("key-1", request))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("different request body");
        verify(paymentProcessor, never()).process(any());
    }

    @Test
    void rejectsWhileOriginalRequestStillInFlight() throws Exception {
        IdempotencyRecord claimOnly = new IdempotencyRecord("key-1", hashOf(request));
        when(idempotencyService.find("key-1")).thenReturn(Optional.of(claimOnly));

        assertThatThrownBy(() -> paymentService.create("key-1", request))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("still being processed");
    }

    @Test
    void releasesClaimWhenExecutionFails() {
        when(idempotencyService.find("key-1")).thenReturn(Optional.empty());
        when(paymentProcessor.process(any())).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> paymentService.create("key-1", request))
                .isInstanceOf(IllegalStateException.class);

        verify(idempotencyService).release("key-1");
    }
}
