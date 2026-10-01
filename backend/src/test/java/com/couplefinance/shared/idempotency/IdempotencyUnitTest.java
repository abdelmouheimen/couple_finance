package com.couplefinance.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.couplefinance.shared.error.ApplicationException;
import org.junit.jupiter.api.Test;

/** Request canonicalisation and key validation (BR-EXP-13). */
class IdempotencyUnitTest {

    @Test
    void BR_EXP_13_same_request_hashes_identically_whatever_the_field_order() {
        byte[] a = RequestHash.forOperation("expense.create").field("amount", "12.50").field("label", "Lunch").build();
        byte[] b = RequestHash.forOperation("expense.create").field("label", "Lunch").field("amount", "12.50").build();

        assertThat(a).hasSize(32).isEqualTo(b);
    }

    @Test
    void BR_EXP_13_different_field_value_operation_or_field_name_changes_the_hash() {
        byte[] base = RequestHash.forOperation("op").field("a", "1").build();

        assertThat(RequestHash.forOperation("op").field("a", "2").build()).isNotEqualTo(base);
        assertThat(RequestHash.forOperation("other").field("a", "1").build()).isNotEqualTo(base);
        assertThat(RequestHash.forOperation("op").field("b", "1").build()).isNotEqualTo(base);
        assertThat(RequestHash.forOperation("op").field("a", "1").field("b", "2").build()).isNotEqualTo(base);
    }

    @Test
    void canonical_form_is_unambiguous() {
        // Without length prefixes these would both concatenate to "a=b;c".
        assertThat(RequestHash.forOperation("op").field("a", "b;c").build())
                .isNotEqualTo(RequestHash.forOperation("op").field("a", "b").field("c", "").build());
        assertThat(RequestHash.forOperation("op").field("a", null).build())
                .isNotEqualTo(RequestHash.forOperation("op").field("a", "").build());
    }

    @Test
    void hash_is_deterministic_across_calls_for_non_ascii_values() {
        assertThat(RequestHash.forOperation("op").field("a", "café ☕").build())
                .isEqualTo(RequestHash.forOperation("op").field("a", "café ☕").build());
    }

    @Test
    void BR_EXP_13_key_is_accepted_up_to_100_visible_ascii_characters() {
        assertThat(IdempotencyKey.fromHeader("a".repeat(100))).isPresent();
        assertThat(IdempotencyKey.fromHeader("0190b3c4-7e1a-7a3b-8c2d-0123456789ab")).isPresent();
        assertThat(IdempotencyKey.fromHeader(null)).isEmpty();
    }

    @Test
    void BR_EXP_13_malformed_keys_are_rejected_with_a_stable_code() {
        for (String bad : new String[] {"", "a".repeat(101), "has space", "tab\t", "nul\u0000", "accentué"}) {
            assertThatThrownBy(() -> IdempotencyKey.fromHeader(bad))
                    .isInstanceOfSatisfying(ApplicationException.class,
                            e -> assertThat(e.errorCode().code()).isEqualTo("IDEMPOTENCY_KEY_INVALID"));
        }
    }

    @Test
    void key_value_is_never_printed() {
        assertThat(new IdempotencyKey("secret-key").toString()).doesNotContain("secret");
    }

    @Test
    void error_codes_map_to_the_documented_statuses() {
        assertThat(IdempotencyErrorCode.REQUEST_IN_PROGRESS.status().value()).isEqualTo(409);
        assertThat(IdempotencyErrorCode.IDEMPOTENCY_KEY_REUSED.status().value()).isEqualTo(422);
        assertThat(IdempotencyErrorCode.IDEMPOTENCY_KEY_INVALID.status().value()).isEqualTo(400);
    }
}
