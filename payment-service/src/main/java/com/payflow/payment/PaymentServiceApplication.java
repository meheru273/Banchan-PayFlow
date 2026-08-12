package com.payflow.payment;

import com.payflow.payment.service.DemoDataService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.payflow.payment", "com.payflow.common"})
@EntityScan("com.payflow.common.domain")
@EnableJpaRepositories("com.payflow.common.repo")
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }

    @Bean
    CommandLineRunner seedDemoData(DemoDataService demoDataService) {
        return args -> demoDataService.seedIfEmpty();
    }
}
