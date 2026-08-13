package com.payflow.payment.security;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Stateless JWT security. Everything demo-facing stays public (this is a
 * portfolio API); mutating admin operations require a bearer token with the
 * ADMIN role. The webhook endpoint is public here because it has its own
 * authentication: the HMAC signature.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(authorities);

        http.csrf(AbstractHttpConfigurer::disable)
                .cors(org.springframework.security.config.Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/demo/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(entryPoint()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint())
                        .accessDeniedHandler(deniedHandler()));
        return http.build();
    }

    @Bean
    SecretKey jwtKey(@Value("${payflow.security.jwt-secret:}") String secret) throws Exception {
        if (secret == null || secret.isBlank()) {
            log.warn("JWT_SECRET is not set — using an insecure development secret. "
                    + "Set it in any real environment.");
            secret = "payflow-dev-only-insecure-jwt-secret";
        }
        // Any-length secret → 256-bit HS256 key, so generated secrets work as-is.
        byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                .digest(secret.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtKey) {
        return NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
    }

    private AuthenticationEntryPoint entryPoint() {
        return (request, response, ex) ->
                writeProblem(response, 401, "Unauthorized", "Missing or invalid bearer token");
    }

    private AccessDeniedHandler deniedHandler() {
        return (request, response, ex) ->
                writeProblem(response, 403, "Forbidden", "This operation requires the ADMIN role");
    }

    private void writeProblem(HttpServletResponse response, int status, String title, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write(
                "{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\"}"
                        .formatted(title, status, detail));
    }
}
