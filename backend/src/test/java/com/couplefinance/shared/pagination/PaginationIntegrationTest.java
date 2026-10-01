package com.couplefinance.shared.pagination;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.couplefinance.support.IntegrationTest;
import com.couplefinance.support.TestTokens;
import com.couplefinance.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@IntegrationTest
class PaginationIntegrationTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    TestUsers users;

    private MvcTestResult list(UUID user, String query) {
        return mvc.get().uri("/test-support/pagination" + query)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange();
    }

    private String nextCursor(MvcTestResult result) {
        return (String) assertThat(result).hasStatusOk().bodyJson().extractingPath("$.nextCursor").actual();
    }

    @Test
    void pages_follow_the_next_cursor_until_exhausted_without_gaps_or_duplicates() {
        UUID user = users.active();
        int total = 0;
        String cursor = null;
        do {
            MvcTestResult result = list(user, "?limit=100" + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(result).hasStatusOk().bodyJson()
                    .extractingPath("$.items[0]").isEqualTo(user + "-item-" + String.format("%03d", total));
            total += ((java.util.List<?>) assertThat(result).bodyJson().extractingPath("$.items").actual()).size();
            cursor = nextCursor(result);
        } while (cursor != null);

        assertThat(total).isEqualTo(250);
    }

    @Test
    void a_limit_above_the_maximum_is_capped_to_100() {
        assertThat(list(users.active(), "?limit=1000")).hasStatusOk().bodyJson()
                .extractingPath("$.items.length()").isEqualTo(100);
    }

    @Test
    void a_limit_below_one_is_a_400_problem() {
        assertThat(list(users.active(), "?limit=0")).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_LIMIT");
        assertThat(list(users.active(), "?limit=abc")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void a_tampered_or_malformed_cursor_is_a_400_problem() {
        UUID user = users.active();
        String cursor = nextCursor(list(user, "?limit=1"));
        char last = cursor.charAt(cursor.length() - 1);
        String tampered = cursor.substring(0, cursor.length() - 1) + (last == 'A' ? 'B' : 'A');

        for (String bad : new String[] {tampered, "garbage", "AAAA"}) {
            assertThat(list(user, "?cursor=" + bad)).hasStatus(HttpStatus.BAD_REQUEST)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyJson().extractingPath("$.code").isEqualTo("INVALID_CURSOR");
        }
    }

    @Test
    void a_cursor_issued_to_one_user_replayed_by_another_is_refused_and_leaks_nothing() {
        UUID alice = users.active();
        UUID bob = users.active();
        String cursor = nextCursor(list(alice, "?limit=1"));

        MvcTestResult replay = list(bob, "?cursor=" + cursor);

        assertThat(replay).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .extractingPath("$.code").isEqualTo("INVALID_CURSOR");
        assertThat(replay).bodyText().doesNotContain(alice.toString());
        assertThat(list(alice, "?cursor=" + cursor)).hasStatusOk();
    }

    @Test
    void unauthenticated_access_is_rejected() {
        assertThat(mvc.get().uri("/test-support/pagination")).hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
