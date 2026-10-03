package com.couplefinance.identity.infrastructure;

import java.net.MalformedURLException;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
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

    /** The single active signing key (key rotation is out of scope). */
    @Bean
    ECKey accessTokenSigningKey(JwtProperties properties) throws ParseException, JOSEException {
        String configured = properties.signingJwk();
        if (configured == null || configured.isBlank()) {
            log.warn("couplefinance.security.jwt.signing-jwk is not set: an ephemeral signing key is generated. "
                    + "Never run production like this.");
            return new ECKeyGenerator(Curve.P_256).keyID(UUID.randomUUID().toString())
                    .algorithm(JWSAlgorithm.ES256).generate();
        }
        ECKey key = ECKey.parse(configured);
        if (!Curve.P_256.equals(key.getCurve()) || !key.isPrivate()) {
            throw new IllegalStateException("couplefinance.security.jwt.signing-jwk must be a private P-256 key.");
        }
        return key;
    }

    @Bean
    JWKSource<SecurityContext> accessTokenKeySource(JwtProperties properties, ECKey accessTokenSigningKey)
            throws MalformedURLException {
        if (properties.jwkSetUri() == null) {
            return new ImmutableJWKSet<>(new JWKSet(accessTokenSigningKey.toPublicJWK()));
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
