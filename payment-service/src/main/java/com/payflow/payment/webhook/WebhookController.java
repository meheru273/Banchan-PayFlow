package com.payflow.payment.webhook;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks")
@Tag(name = "Webhooks")
public class WebhookController {

    private final PaymentWebhookService webhookService;

    public WebhookController(PaymentWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping(value = "/provider", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Provider confirmation webhook",
            description = """
                    HMAC-SHA256 signed: X-Webhook-Signature = hex(HMAC(secret, timestamp + "." + body)), \
                    with X-Webhook-Timestamp in epoch seconds. Tampered signatures and stale timestamps \
                    are rejected with 401.""")
    public ResponseEntity<Void> provider(
            @RequestHeader(WebhookSignature.SIGNATURE_HEADER) String signature,
            @RequestHeader(WebhookSignature.TIMESTAMP_HEADER) long timestamp,
            @RequestBody String rawBody) {
        webhookService.handle(signature, timestamp, rawBody);
        return ResponseEntity.ok().build();
    }
}
