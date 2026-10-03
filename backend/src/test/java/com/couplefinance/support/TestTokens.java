package com.couplefinance.support;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.function.Consumer;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Issues real ES256-signed access tokens for tests, verified by the production {@code JwtDecoder} through the
 * test key source of {@link TestSecurityConfiguration}.
 */
public final class TestTokens {

    public static final String AUDIENCE = "couplefinance-api";

    private final ECKey signingKey;

    TestTokens() {
        try {
            this.signingKey = new ECKeyGenerator(Curve.P_256).keyID("test-key").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    ECKey signingKey() {
        return signingKey;
    }

    ECKey publicKey() {
        return signingKey.toPublicJWK();
    }

    /** "Authorization" header value for a valid token of the given user. */
    public String bearer(UUID userId) {
        return "Bearer " + token(claims -> claims.subject(userId.toString()));
    }

    /** "Authorization" header value for a valid token, adjusted by {@code customizer}. */
    public String bearer(UUID userId, Consumer<JWTClaimsSet.Builder> customizer) {
        return "Bearer " + token(claims -> {
            claims.subject(userId.toString());
            customizer.accept(claims);
        });
    }

    /** Token signed by a key the application does not trust. */
    public String bearerSignedByUnknownKey(UUID userId) {
        try {
            ECKey otherKey = new ECKeyGenerator(Curve.P_256).keyID("test-key").generate();
            return "Bearer " + sign(otherKey, defaultClaims().subject(userId.toString()).build());
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private String token(Consumer<JWTClaimsSet.Builder> customizer) {
        JWTClaimsSet.Builder claims = defaultClaims();
        customizer.accept(claims);
        return sign(signingKey, claims.build());
    }

    private static JWTClaimsSet.Builder defaultClaims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .audience(AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(15))))
                .claim("sid", UUID.randomUUID().toString());
    }

    private static String sign(ECKey key, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(),
                    claims);
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
