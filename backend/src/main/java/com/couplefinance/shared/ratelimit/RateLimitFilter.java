package com.couplefinance.shared.ratelimit;

import java.io.IOException;
import java.util.Optional;

import com.couplefinance.shared.ratelimit.RateLimiter.Stage;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Enforces one {@link Stage} of the rate limit. The {@code IP} stage runs before authentication (brute force,
 * enumeration, invalid tokens); the {@code USER} stage runs after the bearer token is authenticated and keys on the
 * principal, never on client-supplied data.
 *
 * <p>The client address is {@code request.getRemoteAddr()}. Behind a reverse proxy, configure
 * {@code server.forward-headers-strategy} so the container resolves the real client; this filter never reads
 * {@code X-Forwarded-For} itself (it is spoofable).
 *
 * <p>Neither tokens, addresses nor user ids are logged.
 */
public final class RateLimitFilter extends OncePerRequestFilter {

    private static final String ACTUATOR_PREFIX = "/actuator/";

    private final RateLimiter limiter;
    private final Stage stage;
    private final HandlerExceptionResolver exceptionResolver;

    public RateLimitFilter(RateLimiter limiter, Stage stage, HandlerExceptionResolver exceptionResolver) {
        this.limiter = limiter;
        this.stage = stage;
        this.exceptionResolver = exceptionResolver;
    }

    /** The IP and USER instances of this class are both in the chain: each needs its own once-per-request marker. */
    @Override
    protected String getAlreadyFilteredAttributeName() {
        return RateLimitFilter.class.getName() + "." + stage + ".FILTERED";
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !limiter.enabled() || request.getRequestURI().startsWith(ACTUATOR_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> key = key(request);
        if (key.isPresent()) {
            String profile = limiter.profileFor(request.getRequestURI());
            Optional<Long> retryAfter = limiter.tryConsume(stage, profile, key.get());
            if (retryAfter.isPresent()) {
                logger.debug("Rate limit exceeded: stage=" + stage + " profile=" + profile);
                exceptionResolver.resolveException(request, response, null,
                        new RateLimitExceededException(retryAfter.get()));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private Optional<String> key(HttpServletRequest request) {
        if (stage == Stage.IP) {
            return Optional.ofNullable(request.getRemoteAddr());
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        return Optional.ofNullable(authentication.getName());
    }
}
