package com.couplefinance.shared.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Hourly purge of expired idempotency keys (BR-EXP-13: validity 24 h; database.md retention). Expired rows are
 * already ignored (and taken over) by the claim, so the schedule only bounds storage. The DELETE is idempotent:
 * concurrent runs on several instances are harmless.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class IdempotencyPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeJob.class);

    private final IdempotencyService service;

    IdempotencyPurgeJob(IdempotencyService service) {
        this.service = service;
    }

    @Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT1H")
    void purge() {
        try {
            int removed = service.purgeExpired();
            log.info("Purged {} expired idempotency keys", removed);
        } catch (RuntimeException e) {
            log.error("Idempotency key purge failed", e);
        }
    }
}
