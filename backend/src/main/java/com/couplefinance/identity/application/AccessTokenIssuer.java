package com.couplefinance.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.couplefinance.identity.infrastructure.JwtProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

/**
 * Issues ES256 access tokens (security.md §3): claims {@code sub} (user id), {@code sid} (session id), {@code iat},
 * {@code exp}, {@code aud} and {@code iss} when configured. The token carries no household id: the household
 * context is always derived server-side.
 */
@Component
class AccessTokenIssuer {

    private final ECKey signingKey;
    private final JwtProperties properties;
    private final Clock clock;

    AccessTokenIssuer(ECKey accessTokenSigningKey, JwtProperties properties, Clock clock) {
        this.signingKey = accessTokenSigningKey;
        this.properties = properties;
        this.clock = clock;
    }

    Duration ttl() {
        return properties.accessTokenTtl();
    }

    String issue(UUID userId, UUID sessionId) {
        Instant now = clock.instant();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .claim("sid", sessionId.toString())
                .audience(properties.audience())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(properties.accessTokenTtl())));
        if (properties.issuer() != null && !properties.issuer().isBlank()) {
            claims.issuer(properties.issuer());
        }
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256)
                    .keyID(signingKey.getKeyID()).type(JOSEObjectType.JWT).build(), claims.build());
            jwt.sign(new ECDSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign the access token.", e);
        }
    }
}
