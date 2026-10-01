package com.couplefinance.shared.concurrency;

import java.util.UUID;

import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints exercising the ETag / If-Match helpers the way a real update endpoint does (Issue #5).
 * Resources are owner-scoped, so a foreign resource is a 404 before any precondition is evaluated.
 */
@Hidden
@RestController
@RequestMapping("/test-support/versioned")
class VersionedProbeController {

    record Rename(String name) {}

    private final VersionedProbeRepository repository;
    private final JdbcClient jdbc;

    VersionedProbeController(VersionedProbeRepository repository, JdbcClient jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    ResponseEntity<String> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        VersionedProbe probe = load(jwt, id);
        return ResponseEntity.ok().eTag(VersionETag.render(probe.version())).body(probe.name());
    }

    @PutMapping("/{id}")
    @Transactional
    ResponseEntity<String> rename(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch, @RequestBody Rename body) {
        VersionedProbe probe = load(jwt, id);
        VersionETag.requireMatch(VersionETag.requireIfMatch(ifMatch), probe.version());
        probe.rename(body.name());
        VersionedProbe saved = repository.saveAndFlush(probe);
        return ResponseEntity.ok().eTag(VersionETag.render(saved.version())).body(saved.name());
    }

    /** Skips the If-Match comparison and loses a race on purpose, to prove the persistence-level 412. */
    @PutMapping("/{id}/lost-update")
    @Transactional
    ResponseEntity<String> lostUpdate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody Rename body) {
        VersionedProbe probe = load(jwt, id);
        jdbc.sql("UPDATE test_support.versioned_probe SET version = version + 1 WHERE id = :id")
                .param("id", id).update();
        probe.rename(body.name());
        repository.saveAndFlush(probe);
        return ResponseEntity.ok().build();
    }

    private VersionedProbe load(Jwt jwt, UUID id) {
        return repository.findByIdAndOwnerUserId(id, UUID.fromString(jwt.getSubject()))
                .orElseThrow(() -> new ApplicationException(CommonErrorCode.RESOURCE_NOT_FOUND,
                        "The resource was not found."));
    }
}
