package com.payflow.payment.api;

import com.payflow.payment.api.dto.CreatePaymentRequest;
import com.payflow.payment.api.dto.PaymentResponse;
import com.payflow.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create a payment",
            description = """
                    Requires an Idempotency-Key header. Replaying the same key with the same body \
                    returns the originally stored response (marked with Idempotency-Replayed: true); \
                    the same key with a different body is rejected with 409.""")
    public ResponseEntity<String> create(
            @Parameter(description = "Unique key chosen by the client, e.g. a UUID", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be a non-blank string of at most 100 characters");
        }
        PaymentService.CreatePaymentResult result = paymentService.create(idempotencyKey, request);

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Replayed", String.valueOf(result.replayed()));
        if (result.paymentId() != null) {
            builder.location(URI.create("/api/v1/payments/" + result.paymentId()));
        }
        return builder.body(result.bodyJson());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a payment by id")
    public PaymentResponse get(@PathVariable UUID id) {
        return paymentService.getPayment(id);
    }
}
