package com.couplefinance.identity.infrastructure;

import com.couplefinance.identity.application.EmailDelivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Placeholder outside local/test until the production email provider adapter is added (selected separately):
 * messages are dropped with a content-free warning, so the application still starts and never leaks a token to logs.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!local & !test")
class UnconfiguredEmailDeliveryConfiguration {

    private static final Logger log = LoggerFactory.getLogger(UnconfiguredEmailDeliveryConfiguration.class);

    @Bean
    EmailDelivery unconfiguredEmailDelivery() {
        return message -> log.warn("No email provider configured: a message was not delivered.");
    }
}
