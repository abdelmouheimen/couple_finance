package com.couplefinance.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.couplefinance.identity.infrastructure.JwtProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

/** Access-token claims and lifetime (security.md §3), with a fixed clock. */
class AccessTokenIssuerTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @Test
    void token_is_es256_with_the_required_claims_a_15_minute_lifetime_and_no_household() throws Exception {
        ECKey key = new ECKeyGenerator(Curve.P_256).keyID("k").generate();
        AccessTokenIssuer issuer = new AccessTokenIssuer(key, properties(null), clock);

        SignedJWT jwt = SignedJWT.parse(issuer.issue(userId, sessionId));

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(jwt.verify(new ECDSAVerifier(key.toPublicJWK()))).isTrue();
        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.getStringClaim("sid")).isEqualTo(sessionId.toString());
        assertThat(claims.getAudience()).containsExactly("couplefinance-api");
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(NOW);
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(claims.getIssuer()).isNull();
        assertThat(claims.getClaims().keySet()).containsExactlyInAnyOrder("sub", "sid", "aud", "iat", "exp");
    }

    @Test
    void issuer_claim_is_added_when_configured() throws Exception {
        ECKey key = new ECKeyGenerator(Curve.P_256).generate();
        AccessTokenIssuer issuer = new AccessTokenIssuer(key, properties("https://issuer.example"), clock);

        SignedJWT jwt = SignedJWT.parse(issuer.issue(userId, sessionId));

        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("https://issuer.example");
    }

    @Test
    void token_signed_by_another_key_does_not_verify() throws Exception {
        ECKey key = new ECKeyGenerator(Curve.P_256).generate();
        ECKey other = new ECKeyGenerator(Curve.P_256).generate();
        AccessTokenIssuer issuer = new AccessTokenIssuer(key, properties(null), clock);

        SignedJWT jwt = SignedJWT.parse(issuer.issue(userId, sessionId));

        assertThat(jwt.verify(new ECDSAVerifier(other.toPublicJWK()))).isFalse();
    }

    private static JwtProperties properties(String issuer) {
        return new JwtProperties(null, issuer, "couplefinance-api", null, Duration.ofMinutes(15),
                Duration.ofDays(30));
    }
}
