package com.couplefinance.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
class RateLimitIntegrationTest {

    private static final String CACHE_CONTROL_NO_STORE = "no-cache, no-store, max-age=0, must-revalidate";
    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger();

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    TestUsers users;

    /** A client address no other test uses (all tests share one context and one limiter). */
    private static String freshAddress() {
        int n = NEXT_ADDRESS.incrementAndGet();
        return "10." + (n / 65536) + "." + (n / 256 % 256) + "." + (n % 256);
    }

    private MvcTestResult get(String path, String address, String bearer) {
        MockMvcTester.MockMvcRequestBuilder request = mvc.get().uri(path).with(r -> {
            r.setRemoteAddr(address);
            return r;
        });
        if (bearer != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, bearer);
        }
        return request.exchange();
    }

    @Test
    void per_ip_limit_returns_429_problem_details_with_retry_after() {
        String address = freshAddress();
        String bearer = tokens.bearer(users.active());
        for (int i = 0; i < 3; i++) {
            assertThat(get("/test-support/ratelimit/ip/1", address, bearer)).hasStatusOk();
        }

        MvcTestResult limited = get("/test-support/ratelimit/ip/1", address, bearer);

        assertThat(limited).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.code").isEqualTo("RATE_LIMITED");
        assertThat(Long.parseLong(limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER))).isPositive();
    }

    @Test
    void per_ip_limit_applies_before_authentication_and_is_per_address() {
        String address = freshAddress();
        for (int i = 0; i < 3; i++) {
            assertThat(get("/test-support/ratelimit/ip/1", address, null)).hasStatus(HttpStatus.UNAUTHORIZED);
        }

        assertThat(get("/test-support/ratelimit/ip/1", address, null)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(get("/test-support/ratelimit/ip/1", freshAddress(), null)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void per_user_limit_follows_the_principal_across_addresses_and_isolates_other_users() {
        String user = tokens.bearer(users.active());
        String other = tokens.bearer(users.active());
        for (int i = 0; i < 3; i++) {
            assertThat(get("/test-support/ratelimit/user/1", freshAddress(), user)).hasStatusOk();
        }

        MvcTestResult limited = get("/test-support/ratelimit/user/1", freshAddress(), user);

        assertThat(limited).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(get("/test-support/ratelimit/user/1", freshAddress(), other)).hasStatusOk();
    }

    @Test
    void the_429_body_does_not_disclose_the_token_the_user_or_the_address() {
        UUID user = users.active();
        String bearer = tokens.bearer(user);
        String address = freshAddress();
        for (int i = 0; i < 3; i++) {
            get("/test-support/ratelimit/user/1", address, bearer);
        }

        MvcTestResult limited = get("/test-support/ratelimit/user/1", address, bearer);

        assertThat(limited).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited).bodyText().doesNotContain(user.toString()).doesNotContain(address)
                .doesNotContain(bearer.substring("Bearer ".length()));
    }

    @Test
    void authenticated_responses_are_never_cacheable() {
        assertThat(get("/test-support/pagination", freshAddress(), tokens.bearer(users.active())))
                .hasStatusOk()
                .headers().hasValue(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE);
    }

    @Test
    void error_responses_of_authenticated_requests_are_not_cacheable_either() {
        assertThat(get("/test-support/pagination?limit=0", freshAddress(), tokens.bearer(users.active())))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .headers().hasValue(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE);
    }

    @Test
    void rate_limit_responses_are_not_cacheable() {
        String address = freshAddress();
        for (int i = 0; i < 3; i++) {
            get("/test-support/ratelimit/ip/1", address, null);
        }

        assertThat(get("/test-support/ratelimit/ip/1", address, null))
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .headers().hasValue(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE);
    }

    @Test
    void actuator_health_is_not_rate_limited() {
        String address = freshAddress();
        for (int i = 0; i < 400; i++) {
            assertThat(get("/actuator/health", address, null)).hasStatusOk();
        }
    }
}
