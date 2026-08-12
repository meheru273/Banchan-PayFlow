package com.payflow.payment.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI payflowOpenApi() {
        return new OpenAPI().info(new Info()
                .title("PayFlow API")
                .version("v1")
                .description("""
                        Payment + double-entry wallet service. Create a payment with an \
                        Idempotency-Key header, then inspect the wallet's balanced ledger entries. \
                        Amounts are simulated — no real money or card data is involved."""));
    }
}
