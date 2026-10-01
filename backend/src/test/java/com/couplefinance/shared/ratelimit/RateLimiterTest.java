package com.couplefinance.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.couplefinance.shared.ratelimit.RateLimitProperties.Limit;
import com.couplefinance.shared.ratelimit.RateLimitProperties.Profile;
import com.couplefinance.shared.ratelimit.RateLimitProperties.Route;
import com.couplefinance.shared.ratelimit.RateLimiter.Stage;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private static RateLimiter limiter(int maxKeys) {
        Limit three = new Limit(3, Duration.ofHours(1));
        Limit many = new Limit(1000, Duration.ofHours(1));
        return new RateLimiter(new RateLimitProperties(true, maxKeys,
                Map.of("strict", new Profile(three, three), "lenient", new Profile(many, many)),
                List.of(new Route("/api/v1/auth/**", "strict"))));
    }

    @Test
    void requests_beyond_the_capacity_are_refused_with_a_retry_delay() {
        RateLimiter limiter = limiter(10);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryConsume(Stage.IP, "strict", "1.1.1.1")).isEmpty();
        }
        assertThat(limiter.tryConsume(Stage.IP, "strict", "1.1.1.1"))
                .hasValueSatisfying(seconds -> assertThat(seconds).isBetween(1L, 3600L));
    }

    @Test
    void keys_and_stages_have_independent_buckets() {
        RateLimiter limiter = limiter(10);
        for (int i = 0; i < 3; i++) {
            limiter.tryConsume(Stage.IP, "strict", "1.1.1.1");
        }

        assertThat(limiter.tryConsume(Stage.IP, "strict", "2.2.2.2")).isEmpty();
        assertThat(limiter.tryConsume(Stage.USER, "strict", "1.1.1.1")).isEmpty();
        assertThat(limiter.tryConsume(Stage.IP, "lenient", "1.1.1.1")).isEmpty();
    }

    @Test
    void the_number_of_tracked_keys_is_bounded_by_evicting_the_least_recently_used() {
        RateLimiter limiter = limiter(2);
        for (int i = 0; i < 3; i++) {
            limiter.tryConsume(Stage.IP, "strict", "a");
        }
        limiter.tryConsume(Stage.IP, "strict", "b");
        limiter.tryConsume(Stage.IP, "strict", "c");

        // "a" was evicted and starts with a fresh bucket again.
        assertThat(limiter.tryConsume(Stage.IP, "strict", "a")).isEmpty();
    }

    @Test
    void routes_select_the_profile_first_match_wins_else_default() {
        RateLimiter limiter = limiter(10);

        assertThat(limiter.profileFor("/api/v1/auth/login")).isEqualTo("strict");
        assertThat(limiter.profileFor("/api/v1/expenses")).isEqualTo("default");
    }

    @Test
    void an_unknown_profile_in_a_route_is_a_configuration_error() {
        assertThatThrownBy(() -> new RateLimitProperties(true, 10, Map.of(), List.of(new Route("/x", "nope"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
