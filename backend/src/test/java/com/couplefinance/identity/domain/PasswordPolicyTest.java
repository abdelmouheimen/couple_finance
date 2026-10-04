package com.couplefinance.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Password policy of registration (security.md section 3): 10 to 128 characters. */
class PasswordPolicyTest {

    @Test
    void length_boundaries_are_inclusive() {
        assertThat(PasswordPolicy.isAcceptable("a".repeat(9))).isFalse();
        assertThat(PasswordPolicy.isAcceptable("a".repeat(10))).isTrue();
        assertThat(PasswordPolicy.isAcceptable("a".repeat(128))).isTrue();
        assertThat(PasswordPolicy.isAcceptable("a".repeat(129))).isFalse();
        assertThat(PasswordPolicy.isAcceptable(null)).isFalse();
    }

    @Test
    void length_counts_code_points_not_utf16_units() {
        assertThat(PasswordPolicy.isAcceptable("😀".repeat(10))).isTrue();
    }

    @Test
    void requireAcceptable_rejects_without_echoing_the_password() {
        assertThatThrownBy(() -> PasswordPolicy.requireAcceptable("short-pw"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("short-pw");
    }
}
