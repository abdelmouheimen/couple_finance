package com.couplefinance.shared.time;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Business code never calls {@code Instant.now()} / {@code LocalDate.now()} directly; it injects this
 * {@link Clock} so that tests can fix time (CLAUDE.md §3).
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
