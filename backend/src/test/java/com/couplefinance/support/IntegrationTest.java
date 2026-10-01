package com.couplefinance.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full application context against a Testcontainers PostgreSQL, with Liquibase migrations applied,
 * {@code MockMvcTester} available (security filters included), {@link TestTokens} to issue real signed access
 * tokens and {@link TestUsers} to seed accounts. All integration tests use this exact combination so that Spring
 * caches one context and one database container for the whole test run.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestSecurityConfiguration.class, TestUsers.class})
@ActiveProfiles("test")
public @interface IntegrationTest {
}
