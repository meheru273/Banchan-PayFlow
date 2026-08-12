package com.payflow.payment.security;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final TokenService tokenService;
    private final String adminUser;
    private final String adminPassword;

    public AuthController(TokenService tokenService,
                          @Value("${payflow.security.admin-user:admin}") String adminUser,
                          @Value("${payflow.security.admin-password:}") String adminPassword) {
        this.tokenService = tokenService;
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("PAYFLOW_ADMIN_PASSWORD is not set — admin login is disabled until it is.");
        }
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {
    }

    @PostMapping("/login")
    @Operation(summary = "Admin login",
            description = "Returns a JWT access + refresh token pair. Use the access token via the Authorize button for admin operations.")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        if (adminPassword == null || adminPassword.isBlank()
                || !constantTimeEquals(request.username(), adminUser)
                || !constantTimeEquals(request.password(), adminPassword)) {
            throw new BadCredentialsException("Invalid username or password");
        }
        return toResponse(tokenService.issue(adminUser));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new token pair")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        String subject = tokenService.subjectFromRefreshToken(request.refreshToken());
        return toResponse(tokenService.issue(subject));
    }

    private TokenResponse toResponse(TokenService.TokenPair pair) {
        return new TokenResponse(pair.accessToken(), pair.refreshToken(), "Bearer", pair.expiresInSeconds());
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
