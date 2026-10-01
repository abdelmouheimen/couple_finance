package com.couplefinance.shared.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the {@link RateLimiter}. The filters are deliberately not beans: they are installed in the Spring
 * Security chain by {@code SecurityConfiguration} (before / after bearer authentication) and must not be
 * auto-registered a second time in the servlet container.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {

    @Bean
    RateLimiter rateLimiter(RateLimitProperties properties) {
        return new RateLimiter(properties);
    }
}
