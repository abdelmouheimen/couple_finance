package com.couplefinance.shared.ratelimit;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Rate-limit configuration ({@code couplefinance.rate-limit.*}). Every value is a configurable default, not a
 * business constant: a <em>profile</em> bundles a per-IP and a per-user limit, and <em>routes</em> assign a profile
 * to request paths (first match wins, otherwise the {@code default} profile). Stricter profiles for
 * authentication, invitation redemption and receipt upload are added by those features through configuration.
 *
 * @param enabled master switch
 * @param maxTrackedKeys upper bound of in-memory buckets per profile and stage; the least recently used bucket is
 *     evicted first, which bounds memory under address-spraying attacks
 * @param profiles named limits
 * @param routes ordered path-pattern to profile assignments (Spring {@code PathPattern} syntax)
 */
@ConfigurationProperties("couplefinance.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("100000") int maxTrackedKeys,
        @DefaultValue Map<String, Profile> profiles,
        @DefaultValue List<Route> routes) {

    public static final String DEFAULT_PROFILE = "default";

    /** A budget of {@code capacity} requests, refilled continuously over {@code period}. */
    public record Limit(int capacity, Duration period) {
        public Limit {
            if (capacity < 1 || period == null || period.isZero() || period.isNegative()) {
                throw new IllegalArgumentException("A rate limit needs a capacity >= 1 and a positive period.");
            }
        }
    }

    /**
     * @param ip budget per client address, enforced before authentication
     * @param user budget per authenticated principal
     */
    public record Profile(Limit ip, Limit user) {}

    public record Route(String pattern, String profile) {}

    public RateLimitProperties {
        if (maxTrackedKeys < 1) {
            throw new IllegalArgumentException("max-tracked-keys must be >= 1");
        }
        Map<String, Profile> all = new LinkedHashMap<>(profiles);
        all.putIfAbsent(DEFAULT_PROFILE,
                new Profile(new Limit(300, Duration.ofMinutes(1)), new Limit(600, Duration.ofMinutes(1))));
        profiles = Map.copyOf(all);
        routes = List.copyOf(routes);
        for (Route route : routes) {
            if (!profiles.containsKey(route.profile())) {
                throw new IllegalArgumentException("Unknown rate-limit profile: " + route.profile());
            }
        }
    }
}
