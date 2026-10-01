package com.couplefinance.identity.infrastructure;

import java.net.URI;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Access-token validation settings (security.md §3).
 *
 * @param jwkSetUri JWKS endpoint publishing the token signing keys. When absent, no key is trusted and every
 *                  bearer token is rejected (secure by default).
 * @param issuer    expected {@code iss} claim; not checked when absent
 * @param audience  required value in the {@code aud} claim
 */
@ConfigurationProperties("couplefinance.security.jwt")
public record JwtProperties(
        @Nullable URI jwkSetUri,
        @Nullable String issuer,
        @DefaultValue("couplefinance-api") String audience) {
}
