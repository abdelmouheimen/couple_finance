package com.couplefinance.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.couplefinance.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class HealthAndSecurityIntegrationTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void health_endpoint_is_public_and_reports_up_with_the_database() {
        assertThat(mvc.get().uri("/actuator/health"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status").isEqualTo("UP");
    }

    @Test
    void health_endpoint_does_not_expose_component_details() {
        assertThat(mvc.get().uri("/actuator/health"))
                .bodyJson()
                .doesNotHavePath("$.components");
    }

    @Test
    void liveness_and_readiness_probes_are_available() {
        assertThat(mvc.get().uri("/actuator/health/liveness")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/health/readiness")).hasStatusOk();
    }

    @Test
    void api_requires_authentication_by_default_and_answers_with_problem_details() {
        assertThat(mvc.get().uri("/api/v1/anything"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .bodyJson()
                .extractingPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    void other_actuator_endpoints_are_not_exposed() {
        assertThat(mvc.get().uri("/actuator/env")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/actuator/env").with(user("someone")))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .extractingPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void unknown_route_for_an_authenticated_user_is_a_not_found_problem() {
        assertThat(mvc.get().uri("/api/v1/does-not-exist").with(user("someone")))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
                    assertThat(json).extractingPath("$.instance").isEqualTo("/api/v1/does-not-exist");
                });
    }

    @Test
    void responses_carry_security_headers() {
        assertThat(mvc.get().uri("/api/v1/anything"))
                .hasHeader("X-Content-Type-Options", "nosniff")
                .headers().hasValue(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate");
    }
}
