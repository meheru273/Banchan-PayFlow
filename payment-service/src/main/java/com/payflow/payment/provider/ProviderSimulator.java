package com.payflow.payment.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.payment.webhook.PaymentWebhookService;
import com.payflow.payment.webhook.ProviderWebhookPayload;
import com.payflow.payment.webhook.WebhookSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Stands in for a real payment provider: shortly after a payment is created it
 * calls back over real HTTP with a signed confirmation webhook — the same
 * path, signature scheme and verification a real provider integration uses.
 */
@Component
public class ProviderSimulator implements ApplicationListener<WebServerInitializedEvent> {

    private static final Logger log = LoggerFactory.getLogger(ProviderSimulator.class);

    private final ObjectMapper objectMapper;
    private final PaymentWebhookService webhookService;
    private final Duration delay;
    private final String configuredUrl;
    private final RestClient restClient = RestClient.create();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "provider-simulator");
        thread.setDaemon(true);
        return thread;
    });

    private volatile int localPort = -1;

    public ProviderSimulator(ObjectMapper objectMapper,
                             PaymentWebhookService webhookService,
                             @Value("${payflow.provider.delay:1500ms}") Duration delay,
                             @Value("${payflow.provider.webhook-url:}") String configuredUrl) {
        this.objectMapper = objectMapper;
        this.webhookService = webhookService;
        this.delay = delay;
        this.configuredUrl = configuredUrl;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        this.localPort = event.getWebServer().getPort();
    }

    public void scheduleCompletion(UUID paymentId, String providerRef) {
        scheduler.schedule(() -> fire(paymentId, providerRef), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void fire(UUID paymentId, String providerRef) {
        try {
            String body = objectMapper.writeValueAsString(
                    new ProviderWebhookPayload(paymentId, providerRef, "succeeded"));
            long timestamp = Instant.now().getEpochSecond();
            String signature = WebhookSignature.sign(webhookService.secret(), timestamp, body);
            restClient.post()
                    .uri(webhookUrl())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(WebhookSignature.TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(WebhookSignature.SIGNATURE_HEADER, signature)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Simulated provider webhook for payment {} failed: {}", paymentId, e.toString());
        }
    }

    private String webhookUrl() {
        if (configuredUrl != null && !configuredUrl.isBlank()) {
            return configuredUrl;
        }
        return "http://localhost:" + localPort + "/api/v1/webhooks/provider";
    }
}
