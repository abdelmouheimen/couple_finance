package com.couplefinance;

import com.couplefinance.support.TestcontainersConfiguration;
import org.springframework.boot.SpringApplication;

/**
 * Runs the application locally against a throw-away Testcontainers PostgreSQL, without Docker Compose:
 * {@code ./gradlew bootTestRun}.
 */
public class TestCoupleFinanceApplication {

    public static void main(String[] args) {
        SpringApplication.from(CoupleFinanceApplication::main)
                .with(TestcontainersConfiguration.class)
                .withAdditionalProfiles("test")
                .run(args);
    }
}
