package com.couplefinance.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.couplefinance.identity.domain.RefreshPolicy.Decision;
import org.junit.jupiter.api.Test;

/** Rotation state machine with a fixed clock (security.md §3, ADR-007 option A). */
class RefreshPolicyTest {

    private static final Instant T0 = Instant.parse("2026-10-03T10:00:00Z");

    private final Session session = new Session(UUID.randomUUID(), UUID.randomUUID(), null, T0);

    private RefreshToken token(Instant issuedAt) {
        return new RefreshToken(UUID.randomUUID(), session.id(), new byte[32], issuedAt,
                issuedAt.plus(Duration.ofDays(30)));
    }

    private static Instant at(Instant base, Duration offset) {
        return Clock.fixed(base.plus(offset), ZoneOffset.UTC).instant();
    }

    @Test
    void a_fresh_token_is_rotated() {
        assertThat(RefreshPolicy.decide(session, token(T0), at(T0, Duration.ofDays(1)))).isEqualTo(Decision.ROTATE);
    }

    @Test
    void a_rotated_token_within_the_grace_window_rotates_the_chain_forward() {
        RefreshToken rotated = token(T0);
        rotated.rotateTo(UUID.randomUUID(), T0);

        assertThat(RefreshPolicy.decide(session, rotated, at(T0, Duration.ofSeconds(29))))
                .isEqualTo(Decision.ROTATE_FORWARD);
        assertThat(RefreshPolicy.decide(session, rotated, T0)).isEqualTo(Decision.ROTATE_FORWARD);
    }

    @Test
    void a_rotated_token_at_or_after_the_grace_window_is_a_replay() {
        RefreshToken rotated = token(T0);
        rotated.rotateTo(UUID.randomUUID(), T0);

        assertThat(RefreshPolicy.decide(session, rotated, at(T0, Duration.ofSeconds(30))))
                .isEqualTo(Decision.REUSE_DETECTED);
        assertThat(RefreshPolicy.decide(session, rotated, at(T0, Duration.ofMinutes(5))))
                .isEqualTo(Decision.REUSE_DETECTED);
    }

    @Test
    void an_expired_token_is_rejected_even_within_the_grace_window() {
        RefreshToken token = token(T0);
        assertThat(RefreshPolicy.decide(session, token, T0.plus(Duration.ofDays(30)))).isEqualTo(Decision.REJECT);
        token.rotateTo(UUID.randomUUID(), T0.plus(Duration.ofDays(30)).minusSeconds(1));
        assertThat(RefreshPolicy.decide(session, token, T0.plus(Duration.ofDays(30)))).isEqualTo(Decision.REJECT);
    }

    @Test
    void a_revoked_session_rejects_every_token() {
        RefreshToken fresh = token(T0);
        session.revoke(SessionRevokeReason.LOGOUT, T0);

        assertThat(RefreshPolicy.decide(session, fresh, at(T0, Duration.ofSeconds(1)))).isEqualTo(Decision.REJECT);
        assertThat(session.revokeReason()).isEqualTo(SessionRevokeReason.LOGOUT);
    }

    @Test
    void the_first_revocation_reason_is_kept() {
        session.revoke(SessionRevokeReason.REUSE_DETECTED, T0);
        session.revoke(SessionRevokeReason.LOGOUT, T0.plusSeconds(5));

        assertThat(session.revokeReason()).isEqualTo(SessionRevokeReason.REUSE_DETECTED);
    }

    @Test
    void only_the_newest_token_of_a_chain_can_be_rotated() {
        RefreshToken rotated = token(T0);
        rotated.rotateTo(UUID.randomUUID(), T0);

        assertThatThrownBy(() -> rotated.rotateTo(UUID.randomUUID(), T0.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
