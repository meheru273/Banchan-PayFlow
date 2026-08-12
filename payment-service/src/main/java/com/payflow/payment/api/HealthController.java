package com.payflow.payment.api;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

/** Plain health endpoint for Render's health check — no actuator dependency needed. */
@RestController
@Hidden
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    /** The bare domain is what ends up on portfolio cards — land visitors on the API docs. */
    @GetMapping("/")
    public ResponseEntity<Void> root() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/swagger-ui.html"))
                .build();
    }
}
