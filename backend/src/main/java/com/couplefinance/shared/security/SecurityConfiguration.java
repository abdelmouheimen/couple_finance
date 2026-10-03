package com.couplefinance.shared.security;

import com.couplefinance.shared.ratelimit.RateLimitFilter;
import com.couplefinance.shared.ratelimit.RateLimiter;
import com.couplefinance.shared.ratelimit.RateLimiter.Stage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Secure by default (CLAUDE.md §6): only the endpoints listed here are public, everything else requires a valid
 * bearer access token (JWT resource server, security.md §3). Token validation rules live in the identity
 * module's {@code JwtDecoder}.
 *
 * <p>Authentication and authorization failures raised inside the filter chain are delegated to the MVC
 * {@link HandlerExceptionResolver}, so they are rendered as Problem Details by {@code GlobalExceptionHandler}
 * like every other error.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    private static final String[] PUBLIC_ENDPOINTS = {
        "/actuator/health", "/actuator/health/**",
        // OpenAPI documentation; only served when springdoc is enabled (local and test profiles).
        "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui.html", "/swagger-ui/**",
        "/error"
    };

    /** Public authentication routes (security.md §3); every other route stays deny-by-default. */
    private static final String[] PUBLIC_AUTH_ENDPOINTS = {"/api/v1/auth/login"};

    private static final String AUTH_PATH_PREFIX = "/api/v1/auth/";
    private static final long AUTH_MAX_BODY_BYTES = 4096;

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
            RateLimiter rateLimiter) throws Exception {
        AuthenticationEntryPoint problemEntryPoint =
                (request, response, ex) -> exceptionResolver.resolveException(request, response, null, ex);
        AccessDeniedHandler problemAccessDeniedHandler =
                (request, response, ex) -> exceptionResolver.resolveException(request, response, null, ex);
        http
                // Stateless bearer-token API for a mobile client: no cookies, no session, hence no CSRF exposure.
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Spring Security's cache-control writer sends "no-cache, no-store, max-age=0, must-revalidate"
                // on every response, so no authenticated response is cacheable (security.md section 5).
                .headers(headers -> headers.cacheControl(Customizer.withDefaults()))
                .addFilterBefore(new RequestSizeLimitFilter(AUTH_PATH_PREFIX, AUTH_MAX_BODY_BYTES, exceptionResolver),
                        BearerTokenAuthenticationFilter.class)
                // Rate limiting: per IP before token authentication, per user right after it.
                .addFilterBefore(new RateLimitFilter(rateLimiter, Stage.IP, exceptionResolver),
                        BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new RateLimitFilter(rateLimiter, Stage.USER, exceptionResolver),
                        BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_AUTH_ENDPOINTS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problemEntryPoint)
                        .accessDeniedHandler(problemAccessDeniedHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemEntryPoint)
                        .accessDeniedHandler(problemAccessDeniedHandler));
        return http.build();
    }
}
