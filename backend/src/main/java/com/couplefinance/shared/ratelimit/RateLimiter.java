package com.couplefinance.shared.ratelimit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.couplefinance.shared.ratelimit.RateLimitProperties.Limit;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * In-memory token-bucket rate limiter (Bucket4j, security.md §5). Buckets are local to the instance: no shared
 * store is introduced. Keys are only held in memory, never logged.
 */
public final class RateLimiter {

    /** The dimension a limit applies to. */
    public enum Stage { IP, USER }

    private record CompiledRoute(PathPattern pattern, String profile) {}

    private final RateLimitProperties properties;
    private final List<CompiledRoute> routes;
    private final Map<String, Map<String, Bucket>> buckets = new ConcurrentHashMap<>();

    public RateLimiter(RateLimitProperties properties) {
        this.properties = properties;
        PathPatternParser parser = new PathPatternParser();
        this.routes = properties.routes().stream()
                .map(route -> new CompiledRoute(parser.parse(route.pattern()), route.profile()))
                .toList();
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /** Profile applying to a request path: first matching route, else the default profile. */
    public String profileFor(String path) {
        PathContainer container = PathContainer.parsePath(path);
        return routes.stream()
                .filter(route -> route.pattern().matches(container))
                .map(CompiledRoute::profile)
                .findFirst()
                .orElse(RateLimitProperties.DEFAULT_PROFILE);
    }

    /**
     * Consumes one token for {@code key} under the profile limit of the stage.
     *
     * @return empty when allowed, otherwise the number of seconds to wait before retrying (at least 1)
     */
    public Optional<Long> tryConsume(Stage stage, String profile, String key) {
        RateLimitProperties.Profile configured = properties.profiles().get(profile);
        Limit limit = stage == Stage.IP ? configured.ip() : configured.user();
        Bucket bucket = bucketFor(stage + ":" + profile, key, limit);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return Optional.empty();
        }
        long nanos = probe.getNanosToWaitForRefill();
        return Optional.of(Math.max(1, TimeUnit.NANOSECONDS.toSeconds(nanos + 999_999_999L)));
    }

    private Bucket bucketFor(String space, String key, Limit limit) {
        Map<String, Bucket> perSpace =
                buckets.computeIfAbsent(space, ignored -> boundedLru(properties.maxTrackedKeys()));
        synchronized (perSpace) {
            return perSpace.computeIfAbsent(key, ignored -> Bucket.builder()
                    .addLimit(Bandwidth.builder().capacity(limit.capacity())
                            .refillGreedy(limit.capacity(), limit.period()).build())
                    .build());
        }
    }

    private static Map<String, Bucket> boundedLru(int max) {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                return size() > max;
            }
        };
    }
}
