package com.couplefinance.identity.infrastructure;

import com.couplefinance.identity.application.EmailDelivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Email delivery outside local/test. With an SMTP relay configured ({@code SPRING_MAIL_HOST}, plus the sender
 * address {@code COUPLEFINANCE_MAIL_FROM}) messages go through {@link SmtpEmailDelivery}; without one they are dropped
 * with a content-free warning, so the application still starts and never leaks a token to logs.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!local & !test")
class ProductionEmailDeliveryConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ProductionEmailDeliveryConfiguration.class);

    @Bean
    EmailDelivery emailDelivery(ObjectProvider<JavaMailSender> mailSender,
                                @Value("${couplefinance.mail.from:}") String from) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.warn("No SMTP relay configured (spring.mail.host): emails will not be delivered.");
            return message -> log.warn("No email provider configured: a message was not delivered.");
        }
        if (from.isBlank()) {
            throw new IllegalStateException("couplefinance.mail.from must be set when spring.mail.host is configured.");
        }
        return new SmtpEmailDelivery(sender, from);
    }
}
