package com.couplefinance.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RefreshTokenSecretsTest {

    @Test
    void secret_carries_256_random_bits_and_is_unique() {
        Set<String> secrets = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String secret = RefreshTokenSecrets.generate();
            assertThat(Base64.getUrlDecoder().decode(secret)).hasSize(32);
            secrets.add(secret);
        }
        assertThat(secrets).hasSize(200);
    }

    @Test
    void stored_hash_is_the_sha256_of_the_secret() throws Exception {
        String secret = RefreshTokenSecrets.generate();

        byte[] hash = RefreshTokenSecrets.hash(secret);

        assertThat(hash).hasSize(32)
                .isEqualTo(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        assertThat(RefreshTokenSecrets.hash(secret)).isEqualTo(hash);
        assertThat(RefreshTokenSecrets.hash(RefreshTokenSecrets.generate())).isNotEqualTo(hash);
    }
}
