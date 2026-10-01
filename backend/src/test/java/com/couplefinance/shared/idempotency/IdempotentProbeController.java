package com.couplefinance.shared.idempotency;

import java.util.UUID;

import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only creating endpoint that opts in to idempotency the way a real one (EXPENSE-001) does. Each execution
 * inserts one row, so tests can count executions.
 */
@Hidden
@RestController
@RequestMapping("/test-support/idempotent")
class IdempotentProbeController {

    /** {@code delayMs} only holds the first request open; it is not part of the canonical request. */
    record Create(String name, long delayMs) {}

    private final IdempotencyService idempotency;
    private final JdbcClient jdbc;

    IdempotentProbeController(IdempotencyService idempotency, JdbcClient jdbc) {
        this.idempotency = idempotency;
        this.jdbc = jdbc;
    }

    @PostMapping
    ResponseEntity<String> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = IdempotencyKey.HEADER, required = false) String keyHeader,
            @RequestBody Create body) {
        UUID user = UUID.fromString(jwt.getSubject());
        IdempotentResponse response;
        boolean replayed = false;
        var key = IdempotencyKey.fromHeader(keyHeader);
        if (key.isPresent()) {
            byte[] hash = RequestHash.forOperation("probe.create").field("name", body.name()).build();
            IdempotentResult result = idempotency.execute(user, key.get(), "probe.create", hash,
                    () -> execute(user, body));
            response = result.response();
            replayed = result.replayed();
        } else {
            response = execute(user, body);
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON).header("Idempotent-Replayed", String.valueOf(replayed));
        return builder.body(response.jsonBody());
    }

    private IdempotentResponse execute(UUID user, Create body) {
        if ("fail".equals(body.name())) {
            throw new ApplicationException(CommonErrorCode.VALIDATION_FAILED, "failing on purpose");
        }
        if (body.delayMs() > 0) {
            try {
                Thread.sleep(body.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO test_support.idempotent_probe (id, owner_user_id, name) VALUES (:id, :owner, :name)")
                .param("id", id).param("owner", user).param("name", body.name()).update();
        return new IdempotentResponse(HttpStatus.CREATED.value(),
                "{\"id\":\"" + id + "\",\"name\":\"" + body.name() + "\"}");
    }
}
