package com.couplefinance.household.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.couplefinance.household.domain.Invitation.HouseholdSeats;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import org.junit.jupiter.api.Test;

/** BR-HH-01, BR-HH-04, BR-HH-10 — Issue #29. */
class InvitationTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
    private static final HouseholdId HOUSEHOLD = new HouseholdId(UUID.randomUUID());
    private static final UserId CREATOR = new UserId(UUID.randomUUID());

    @Test
    void BR_HH_04_code_has_at_least_128_bits() {
        InvitationCode code = InvitationCode.generate(new SecureRandom());

        byte[] decoded = Base64.getUrlDecoder().decode(code.value());
        assertThat(decoded.length * 8).isGreaterThanOrEqualTo(128);
        assertThat(code.value()).doesNotContain("=").matches("[A-Za-z0-9_-]+");
    }

    @Test
    void BR_HH_04_codes_are_unique_and_only_a_32_byte_hash_is_derived() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            InvitationCode code = InvitationCode.generate(new SecureRandom());
            assertThat(codes.add(code.value())).isTrue();
            assertThat(code.hash()).hasSize(32).isNotEqualTo(code.value().getBytes());
        }
    }

    @Test
    void BR_HH_04_hash_is_deterministic_for_redemption_lookup() {
        InvitationCode code = InvitationCode.generate(new SecureRandom());

        assertThat(InvitationCode.of(code.value()).hash()).isEqualTo(code.hash());
    }

    @Test
    void BR_HH_04_code_is_never_exposed_by_toString() {
        InvitationCode code = InvitationCode.generate(new SecureRandom());

        assertThat(code.toString()).doesNotContain(code.value());
    }

    @Test
    void BR_HH_04_invitation_expires_after_7_days() {
        Invitation invitation = Invitation.issue(UUID.randomUUID(), new HouseholdSeats(HOUSEHOLD, true, 1),
                CREATOR, NOW);

        assertThat(invitation.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(invitation.status()).isEqualTo(InvitationStatus.ACTIVE);
        assertThat(invitation.isUsable(NOW.plus(Duration.ofDays(7)).minusSeconds(1))).isTrue();
        assertThat(invitation.isUsable(NOW.plus(Duration.ofDays(7)))).isFalse();
    }

    @Test
    void BR_HH_04_revoked_invitation_is_not_usable() {
        Invitation revoked = new Invitation(UUID.randomUUID(), HOUSEHOLD, CREATOR, NOW, NOW.plusSeconds(60),
                InvitationStatus.REVOKED);

        assertThat(revoked.isUsable(NOW)).isFalse();
    }

    @Test
    void BR_HH_04_invitation_only_for_single_member_active_household() {
        assertThat(new HouseholdSeats(HOUSEHOLD, true, 1).canInvite()).isTrue();
        // BR-HH-01: no free seat
        assertThat(new HouseholdSeats(HOUSEHOLD, true, 2).canInvite()).isFalse();
        // BR-HH-10: dissolved
        assertThat(new HouseholdSeats(HOUSEHOLD, false, 1).canInvite()).isFalse();
        assertThat(new HouseholdSeats(HOUSEHOLD, true, 0).canInvite()).isFalse();
        assertThatThrownBy(() -> Invitation.issue(UUID.randomUUID(), new HouseholdSeats(HOUSEHOLD, true, 2),
                CREATOR, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Invitation.issue(UUID.randomUUID(), new HouseholdSeats(HOUSEHOLD, false, 1),
                CREATOR, NOW)).isInstanceOf(IllegalStateException.class);
    }
}
