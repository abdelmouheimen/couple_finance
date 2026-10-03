package com.couplefinance.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Passwords are verified with Argon2id only (security.md §3). */
class PasswordEncoderConfigurationTest {

    private final PasswordEncoder encoder = new PasswordEncoderConfiguration().passwordEncoder();

    @Test
    void passwords_are_hashed_with_argon2id_and_verified() {
        String encoded = encoder.encode("correct horse battery");

        assertThat(encoded).startsWith("$argon2id$");
        assertThat(encoder.matches("correct horse battery", encoded)).isTrue();
        assertThat(encoder.matches("wrong password", encoded)).isFalse();
    }

    @Test
    void a_hash_of_another_encoder_family_is_rejected_even_for_the_right_password() {
        String bcrypt = new BCryptPasswordEncoder().encode("correct horse battery");

        assertThat(encoder.matches("correct horse battery", bcrypt)).isFalse();
    }
}
