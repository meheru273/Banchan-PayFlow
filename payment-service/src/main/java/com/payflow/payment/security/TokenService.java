package com.payflow.payment.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public TokenService(JwtEncoder encoder,
                        JwtDecoder decoder,
                        @Value("${payflow.security.access-ttl:15m}") Duration accessTtl,
                        @Value("${payflow.security.refresh-ttl:7d}") Duration refreshTtl) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    public record TokenPair(String accessToken, String refreshToken, long expiresInSeconds) {
    }

    public TokenPair issue(String subject) {
        Instant now = Instant.now();
        String access = encode(JwtClaimsSet.builder()
                .issuer("payflow")
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(accessTtl))
                .claim("roles", List.of("ADMIN"))
                .claim("token_use", "access")
                .build());
        String refresh = encode(JwtClaimsSet.builder()
                .issuer("payflow")
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(refreshTtl))
                .claim("token_use", "refresh")
                .build());
        return new TokenPair(access, refresh, accessTtl.toSeconds());
    }

    public String subjectFromRefreshToken(String refreshToken) {
        try {
            Jwt jwt = decoder.decode(refreshToken);
            if (!"refresh".equals(jwt.getClaimAsString("token_use"))) {
                throw new BadCredentialsException("Not a refresh token");
            }
            return jwt.getSubject();
        } catch (JwtException e) {
            throw new BadCredentialsException("Invalid or expired refresh token");
        }
    }

    private String encode(JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
