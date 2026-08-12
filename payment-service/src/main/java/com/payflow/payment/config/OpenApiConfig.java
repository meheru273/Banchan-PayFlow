package com.payflow.payment.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI payflowOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("PayFlow API")
                        .version("v1")
                        .description("""
                                Payment + double-entry wallet service. Create a payment with an \
                                Idempotency-Key header; a simulated provider confirms it via an \
                                HMAC-signed webhook and the balanced ledger entries are posted \
                                asynchronously. Admin operations need a bearer token from \
                                /api/v1/auth/login. Amounts are simulated — no real money or \
                                card data is involved."""))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
