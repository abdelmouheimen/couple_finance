package com.couplefinance.identity.infrastructure;

import java.net.URI;
import java.time.Duration;

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
 * @param signingJwk private ES256 (P-256) key as a JWK JSON document, injected by the deployment secret mechanism
 *                  (never committed). It signs the issued access tokens and its public part is trusted by the
 *                  validator when no {@code jwkSetUri} is set. When absent, an ephemeral key is generated at
 *                  startup (local development only: tokens do not survive a restart nor span instances).
 * @param accessTokenTtl lifetime of an access token (security.md §3: 15 minutes)
 * @param refreshTokenTtl lifetime of a refresh token (security.md §3: 30 days)
 */
@ConfigurationProperties("couplefinance.security.jwt")
public record JwtProperties(
        @Nullable URI jwkSetUri,
        @Nullable String issuer,
        @DefaultValue("couplefinance-api") String audience,
        @Nullable String signingJwk,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("30d") Duration refreshTokenTtl) {

    @Override
    public String toString() {
        // The signing key is a secret: it must never reach a log through this record.
        return "JwtProperties[jwkSetUri=" + jwkSetUri + ", issuer=" + issuer + ", audience=" + audience + "]";
    }
}
