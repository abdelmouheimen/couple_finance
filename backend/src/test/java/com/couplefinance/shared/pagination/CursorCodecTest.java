package com.couplefinance.shared.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import com.couplefinance.shared.error.ApplicationException;
import org.junit.jupiter.api.Test;

class CursorCodecTest {

    private static final byte[] KEY = new byte[32];
    private final CursorCodec codec = new CursorCodec(KEY);

    @Test
    void a_cursor_round_trips_for_the_scope_it_was_issued_for() {
        String cursor = codec.encode("user-1|expenses", List.of("2026-05-01", "0190-abc"));

        assertThat(codec.decode("user-1|expenses", cursor)).containsExactly("2026-05-01", "0190-abc");
    }

    @Test
    void the_cursor_is_opaque_and_does_not_contain_the_position() {
        String cursor = codec.encode("scope", List.of("secret-internal-id"));

        assertThat(cursor).doesNotContain("secret-internal-id");
        assertThat(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.ISO_8859_1))
                .doesNotContain("secret-internal-id");
    }

    @Test
    void a_cursor_replayed_with_another_scope_is_invalid() {
        String cursor = codec.encode("user-1|expenses", List.of("a"));

        assertInvalid("user-2|expenses", cursor);
        assertInvalid("user-1|receipts", cursor);
    }

    @Test
    void a_cursor_issued_with_another_key_is_invalid() {
        byte[] other = new byte[32];
        Arrays.fill(other, (byte) 1);

        assertInvalid("scope", new CursorCodec(other).encode("scope", List.of("a")));
    }

    @Test
    void every_single_byte_tampering_is_detected() {
        byte[] token = Base64.getUrlDecoder().decode(codec.encode("scope", List.of("a", "b")));
        for (int i = 0; i < token.length; i++) {
            byte[] tampered = token.clone();
            tampered[i] ^= 0x01;
            assertInvalid("scope", Base64.getUrlEncoder().withoutPadding().encodeToString(tampered));
        }
    }

    @Test
    void malformed_cursors_are_invalid() {
        assertInvalid("scope", "not base64 !!");
        assertInvalid("scope", "");
        assertInvalid("scope", "AAAA");
        assertInvalid("scope", "A".repeat(2000));
    }

    @Test
    void the_key_must_be_32_bytes() {
        assertThatThrownBy(() -> new CursorCodec(new byte[16])).isInstanceOf(IllegalArgumentException.class);
    }

    private void assertInvalid(String scope, String cursor) {
        assertThatThrownBy(() -> codec.decode(scope, cursor))
                .isInstanceOfSatisfying(ApplicationException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(PaginationErrorCode.INVALID_CURSOR));
    }
}
