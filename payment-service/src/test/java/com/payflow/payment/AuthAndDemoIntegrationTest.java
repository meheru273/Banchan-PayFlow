package com.payflow.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** JWT auth flow and the admin-only demo reset, against the dev profile. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class AuthAndDemoIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    private HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @Test
    void demoResetWithoutTokenIsUnauthorized() {
        ResponseEntity<String> response = rest.postForEntity("/api/v1/demo/reset", null, String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void loginWithWrongPasswordIsUnauthorized() {
        ResponseEntity<String> response = rest.postForEntity("/api/v1/auth/login",
                json("{\"username\":\"admin\",\"password\":\"wrong\"}"), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void loginRefreshAndAdminResetWork() throws Exception {
        // Login with the dev-profile admin credentials
        ResponseEntity<String> login = rest.postForEntity("/api/v1/auth/login",
                json("{\"username\":\"admin\",\"password\":\"demo-admin\"}"), String.class);
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        JsonNode tokens = objectMapper.readTree(login.getBody());
        String accessToken = tokens.get("accessToken").asText();
        String refreshToken = tokens.get("refreshToken").asText();
        assertThat(tokens.get("tokenType").asText()).isEqualTo("Bearer");

        // Access token authorizes the admin-only reset
        HttpHeaders auth = new HttpHeaders();
        auth.setBearerAuth(accessToken);
        ResponseEntity<String> reset = rest.postForEntity("/api/v1/demo/reset",
                new HttpEntity<>(null, auth), String.class);
        assertThat(reset.getStatusCode().value()).isEqualTo(200);
        assertThat(objectMapper.readTree(reset.getBody()).size()).isEqualTo(2);

        // Refresh token yields a fresh pair
        ResponseEntity<String> refreshed = rest.postForEntity("/api/v1/auth/refresh",
                json("{\"refreshToken\":\"" + refreshToken + "\"}"), String.class);
        assertThat(refreshed.getStatusCode().value()).isEqualTo(200);
        assertThat(objectMapper.readTree(refreshed.getBody()).get("accessToken").asText()).isNotBlank();

        // An access token is not accepted as a refresh token
        ResponseEntity<String> misuse = rest.postForEntity("/api/v1/auth/refresh",
                json("{\"refreshToken\":\"" + accessToken + "\"}"), String.class);
        assertThat(misuse.getStatusCode().value()).isEqualTo(401);
    }
}
