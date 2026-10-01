package com.couplefinance.shared.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.couplefinance.shared.ratelimit.RateLimitConfiguration;
import com.couplefinance.shared.security.SecurityConfiguration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.ProbeController.class)
@Import({SecurityConfiguration.class, RateLimitConfiguration.class, GlobalExceptionHandlerTest.ProbeController.class})
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvcTester mvc;

    /** The slice does not load the identity module; requests here authenticate with {@code user(..)}. */
    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void invalid_request_body_is_a_validation_problem_listing_each_field() {
        assertThat(mvc.post().uri("/probe/body").with(user("u"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \" \", \"count\": 0}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
                    assertThat(json).extractingPath("$.errors[*].field").asArray()
                            .containsExactlyInAnyOrder("name", "count");
                });
    }

    @Test
    void invalid_request_parameter_is_a_validation_problem() {
        assertThat(mvc.get().uri("/probe/param?amount=-1").with(user("u")))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
                    assertThat(json).extractingPath("$.errors[0].field").isEqualTo("amount");
                });
    }

    @Test
    void malformed_json_is_a_malformed_request_problem() {
        assertThat(mvc.post().uri("/probe/body").with(user("u"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.code").isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void application_exception_uses_its_own_code_status_and_detail() {
        assertThat(mvc.get().uri("/probe/application-error").with(user("u")))
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.code").isEqualTo("PROBE_CONFLICT");
                    assertThat(json).extractingPath("$.detail").isEqualTo("The probe is in conflict.");
                    assertThat(json).extractingPath("$.instance").isEqualTo("/probe/application-error");
                });
    }

    @Test
    void unexpected_exception_is_an_internal_error_without_leaking_its_message() {
        assertThat(mvc.get().uri("/probe/unexpected").with(user("u")))
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .bodyText()
                .contains("INTERNAL_ERROR")
                .doesNotContain("secret internal detail");
    }

    @Test
    void access_denied_is_a_forbidden_problem() {
        assertThat(mvc.get().uri("/probe/forbidden").with(user("u")))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code").isEqualTo("ACCESS_DENIED");
    }

    @Test
    void unauthenticated_request_is_rejected_before_reaching_the_controller() {
        assertThat(mvc.get().uri("/probe/application-error"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson()
                .extractingPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    void wrong_http_method_is_a_method_not_allowed_problem() {
        assertThat(mvc.delete().uri("/probe/application-error").with(user("u")))
                .hasStatus(HttpStatus.METHOD_NOT_ALLOWED)
                .bodyJson()
                .extractingPath("$.code").isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void unsupported_content_type_is_an_unsupported_media_type_problem() {
        assertThat(mvc.post().uri("/probe/body").with(user("u"))
                .contentType(MediaType.TEXT_PLAIN)
                .content("hello"))
                .hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .bodyJson()
                .extractingPath("$.code").isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    enum ProbeErrorCode implements ErrorCode {
        PROBE_CONFLICT;

        @Override
        public String code() {
            return name();
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.CONFLICT;
        }
    }

    record ProbeRequest(@NotBlank String name, @Positive int count) {}

    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        @PostMapping("/body")
        String body(@Valid @RequestBody ProbeRequest request) {
            return request.name();
        }

        @GetMapping("/param")
        String param(@RequestParam @Min(0) int amount) {
            return String.valueOf(amount);
        }

        @GetMapping("/application-error")
        String applicationError() {
            throw new ApplicationException(ProbeErrorCode.PROBE_CONFLICT, "The probe is in conflict.");
        }

        @GetMapping("/unexpected")
        String unexpected() {
            throw new IllegalStateException("secret internal detail");
        }

        @GetMapping("/forbidden")
        String forbidden() {
            throw new AccessDeniedException("denied");
        }
    }
}
