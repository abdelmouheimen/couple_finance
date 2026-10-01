package com.couplefinance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CoupleFinance backend — modular monolith (ADR-001).
 *
 * <p>Each direct sub-package of {@code com.couplefinance} is an application module (package-by-feature),
 * verified by Spring Modulith in {@code ModularityTests}.
 */
@SpringBootApplication
public class CoupleFinanceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoupleFinanceApplication.class, args);
    }
}
