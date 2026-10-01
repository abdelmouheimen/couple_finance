package com.couplefinance.identity.infrastructure;

import java.net.MalformedURLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Validates access tokens (security.md §3): ES256 signature against the configured key source, {@code exp}
 * required and not expired (60 s clock skew), {@code sub} present, expected audience, and issuer when configured.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
class JwtDecoderConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JwtDecoderConfiguration.class);

    @Bean
    JWKSource<SecurityContext> accessTokenKeySource(JwtProperties properties) throws MalformedURLException {
        if (properties.jwkSetUri() == null) {
            log.warn("couplefinance.security.jwt.jwk-set-uri is not set: no signing key is trusted and every "
                    + "bearer token will be rejected.");
            return new ImmutableJWKSet<>(new JWKSet());
        }
        return JWKSourceBuilder.<SecurityContext>create(properties.jwkSetUri().toURL()).build();
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> accessTokenKeySource, JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(accessTokenKeySource)
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();
        decoder.setJwtValidator(validators(properties));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validators(JwtProperties properties) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        // The default timestamp validator ignores a missing exp: a token without expiry must never be accepted.
        validators.add(new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull));
        validators.add(new JwtClaimValidator<String>(JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank()));
        validators.add(new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                audiences -> audiences != null && audiences.contains(properties.audience())));
        if (properties.issuer() != null && !properties.issuer().isBlank()) {
            validators.add(new JwtIssuerValidator(properties.issuer()));
        }
        return JwtValidators.createDefaultWithValidators(validators);
    }
}
