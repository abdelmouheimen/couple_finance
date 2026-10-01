package com.couplefinance.shared.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
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

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
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
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
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
