package com.couplefinance.shared.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.couplefinance.shared.error.ApplicationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** BR-EXP-12. */
class VersionETagTest {

    @Test
    void BR_EXP_12_etag_is_the_version_as_a_strong_quoted_tag() {
        assertThat(VersionETag.render(0)).isEqualTo("\"0\"");
        assertThat(VersionETag.render(42)).isEqualTo("\"42\"");
    }

    @Test
    void negative_version_cannot_be_rendered() {
        assertThatThrownBy(() -> VersionETag.render(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"7\"", "7", "  \"7\" "})
    void BR_EXP_12_if_match_is_parsed_into_the_expected_version(String header) {
        assertThat(VersionETag.requireIfMatch(header)).isEqualTo(7);
    }

    @Test
    void rendered_etag_round_trips() {
        assertThat(VersionETag.requireIfMatch(VersionETag.render(123456789L))).isEqualTo(123456789L);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void BR_EXP_12_missing_if_match_is_precondition_required(String header) {
        assertThatThrownBy(() -> VersionETag.requireIfMatch(header))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ConcurrencyErrorCode.IF_MATCH_REQUIRED);
                    assertThat(ex.errorCode().status().value()).isEqualTo(428);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "W/\"3\"", "\"3\", \"4\"", "abc", "-1", "1.5", "\"3", "3\"", "\"\"",
            "9999999999999999999", "\"a\""})
    void malformed_if_match_is_a_client_error_never_a_server_error(String header) {
        assertThatThrownBy(() -> VersionETag.requireIfMatch(header))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ConcurrencyErrorCode.IF_MATCH_INVALID);
                    assertThat(ex.errorCode().status().value()).isEqualTo(400);
                });
    }

    @Test
    void BR_EXP_12_stale_version_is_precondition_failed() {
        assertThatThrownBy(() -> VersionETag.requireMatch(2, 3))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ConcurrencyErrorCode.VERSION_CONFLICT);
                    assertThat(ex.errorCode().status().value()).isEqualTo(412);
                });
        assertThatCode(() -> VersionETag.requireMatch(3, 3)).doesNotThrowAnyException();
    }
}
