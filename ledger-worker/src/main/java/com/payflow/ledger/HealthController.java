package com.payflow.ledger;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Render health check; the worker's real job is the RabbitMQ listener. */
@RestController
public class HealthController {

    @GetMapping({"/", "/health"})
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "ledger-worker");
    }
}
