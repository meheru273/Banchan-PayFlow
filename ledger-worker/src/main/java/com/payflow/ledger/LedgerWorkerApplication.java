package com.payflow.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.payflow.ledger", "com.payflow.common"})
@EntityScan("com.payflow.common.domain")
@EnableJpaRepositories("com.payflow.common.repo")
public class LedgerWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerWorkerApplication.class, args);
    }
}
