package com.couplefinance.support;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Makes the production token validation trust the test signing key: only the key source is replaced, the
 * {@code JwtDecoder} and its validators are the real ones.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfiguration {

    @Bean
    TestTokens testTokens() {
        return new TestTokens();
    }

    @Bean
    @Primary
    JWKSource<SecurityContext> testAccessTokenKeySource(TestTokens testTokens) {
        return new ImmutableJWKSet<>(new JWKSet(testTokens.publicKey()));
    }
}
