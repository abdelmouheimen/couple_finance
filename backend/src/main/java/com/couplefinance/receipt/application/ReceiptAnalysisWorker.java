package com.couplefinance.receipt.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.couplefinance.receipt.domain.ReceiptAnalysisStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls the analysis queue (DB-backed, architecture.md D7): claims a small batch in a short transaction (FOR UPDATE
 * SKIP LOCKED, safe on several instances), then processes each job outside any transaction. A crashed worker's jobs
 * come back when their lease expires.
 */
@Component
@EnableScheduling
@EnableConfigurationProperties(ReceiptAnalysisProperties.class)
public class ReceiptAnalysisWorker {

    private static final Logger log = LoggerFactory.getLogger(ReceiptAnalysisWorker.class);

    private final ReceiptAnalysisStore store;
    private final ReceiptAnalysisProcessor processor;
    private final ReceiptAnalysisProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final String workerId = "worker-" + UUID.randomUUID();

    ReceiptAnalysisWorker(ReceiptAnalysisStore store, ReceiptAnalysisProcessor processor,
            ReceiptAnalysisProperties properties, TransactionTemplate transaction, Clock clock) {
        this.store = store;
        this.processor = processor;
        this.properties = properties;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${couplefinance.receipt.analysis.poll-interval}",
            fixedDelayString = "${couplefinance.receipt.analysis.poll-interval}")
    public void poll() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("Receipt analysis poll failed ({})", e.getClass().getSimpleName());
        }
    }

    /** Claims and processes one batch; returns the number of jobs processed. */
    public int runOnce() {
        Instant now = clock.instant();
        List<ReceiptAnalysisStore.Claim> claims = transaction.execute(s -> store.claim(workerId, now,
                now.plus(properties.lease()), properties.batchSize()));
        if (claims == null) {
            return 0;
        }
        for (ReceiptAnalysisStore.Claim claim : claims) {
            processor.process(claim);
        }
        return claims.size();
    }
}
