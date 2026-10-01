package com.couplefinance.shared.ratelimit;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints under paths mapped to the tight rate-limit profiles of application-test.yaml. */
@Hidden
@RestController
@RequestMapping("/test-support/ratelimit")
class RateLimitProbeController {

    @GetMapping("/ip/{id}")
    String ip() {
        return "ok";
    }

    @GetMapping("/user/{id}")
    String user() {
        return "ok";
    }
}
