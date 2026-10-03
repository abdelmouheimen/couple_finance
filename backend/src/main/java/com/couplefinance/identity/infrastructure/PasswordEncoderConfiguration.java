package com.couplefinance.identity.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing (security.md §3): Argon2id with Spring Security's recommended parameters, the encoded string
 * (including salt and parameters) being stored in {@code user_account.password_hash}. Reused by registration.
 * A value encoded by another family (bcrypt, PBKDF2, ...) never matches.
 */
@Configuration(proxyBeanMethods = false)
class PasswordEncoderConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
