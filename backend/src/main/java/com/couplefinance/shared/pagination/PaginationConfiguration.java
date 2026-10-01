package com.couplefinance.shared.pagination;

import java.security.SecureRandom;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CursorProperties.class)
class PaginationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PaginationConfiguration.class);

    @Bean
    CursorCodec cursorCodec(CursorProperties properties) {
        String configured = properties.cursorKey();
        if (configured == null || configured.isBlank()) {
            log.warn("couplefinance.pagination.cursor-key is not set: using a random per-process key, "
                    + "pagination cursors will not survive a restart.");
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            return new CursorCodec(key);
        }
        try {
            return new CursorCodec(Base64.getDecoder().decode(configured.trim()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "couplefinance.pagination.cursor-key must be the base64 encoding of exactly 32 bytes.");
        }
    }
}
