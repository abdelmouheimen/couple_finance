package com.couplefinance.receipt.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.couplefinance.receipt.domain.ReceiptAnalysisStore;
import com.couplefinance.receipt.domain.ReceiptDraft;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.shared.id.UuidV7;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** JDBC adapter of {@link ReceiptAnalysisStore}. Every receipt update is conditional (database-schema.md 8.1). */
@Repository
class JdbcReceiptAnalysisStore implements ReceiptAnalysisStore {

    /** BR-RCP-18: raw AI output is kept 90 days. */
    static final int RAW_RETENTION_DAYS = 90;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    JdbcReceiptAnalysisStore(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void enqueue(UUID receiptId, HouseholdId household, Trigger trigger, Instant now) {
        jdbc.sql("""
                        INSERT INTO receipt.receipt_analysis_job
                            (id, receipt_id, household_id, status, trigger, attempt_count, next_attempt_at,
                             created_at, updated_at)
                        VALUES (:id, :receipt, :household, 'PENDING', :trigger, 0, :now, :now, :now)
                        """)
                .param("id", UuidV7.generate(clock))
                .param("receipt", receiptId)
                .param("household", household.value())
                .param("trigger", trigger.name())
                .param("now", utc(now))
                .update();
    }

    @Override
    public List<Claim> claim(String worker, Instant now, Instant leaseUntil, int limit) {
        // Expired leases (a worker died mid-call) go back to the queue; the attempt is not counted.
        jdbc.sql("""
                        WITH expired AS (
                            UPDATE receipt.receipt_analysis_job
                            SET status = 'PENDING', locked_by = NULL, locked_until = NULL, next_attempt_at = :now,
                                updated_at = :now
                            WHERE status = 'RUNNING' AND locked_until < :now
                            RETURNING receipt_id, household_id)
                        UPDATE receipt.receipt r SET analysis_status = 'PENDING', updated_at = :now
                        FROM expired e
                        WHERE r.id = e.receipt_id AND r.household_id = e.household_id AND r.status = 'OPEN'
                          AND r.analysis_status = 'RUNNING'
                        """)
                .param("now", utc(now))
                .update();
        List<Map<String, Object>> jobs = jdbc.sql("""
                        WITH ready AS (
                            SELECT id FROM receipt.receipt_analysis_job
                            WHERE status = 'PENDING' AND next_attempt_at <= :now
                            ORDER BY next_attempt_at LIMIT :limit
                            FOR UPDATE SKIP LOCKED)
                        UPDATE receipt.receipt_analysis_job j
                        SET status = 'RUNNING', locked_by = :worker, locked_until = :lease, updated_at = :now
                        FROM ready WHERE j.id = ready.id
                        RETURNING j.id, j.receipt_id, j.household_id, j.attempt_count
                        """)
                .param("now", utc(now))
                .param("limit", limit)
                .param("worker", worker)
                .param("lease", utc(leaseUntil))
                .query().listOfRows();
        List<Claim> claims = new ArrayList<>();
        for (Map<String, Object> job : jobs) {
            UUID jobId = (UUID) job.get("id");
            UUID receipt = (UUID) job.get("receipt_id");
            UUID household = (UUID) job.get("household_id");
            List<UUID> uploader = jdbc.sql("""
                            UPDATE receipt.receipt SET analysis_status = 'RUNNING', updated_at = :now
                            WHERE id = :receipt AND household_id = :household AND status = 'OPEN'
                              AND analysis_status = 'PENDING'
                            RETURNING uploaded_by
                            """)
                    .param("now", utc(now)).param("receipt", receipt).param("household", household)
                    .query(UUID.class).list();
            if (uploader.isEmpty()) {
                finishJob(jobId, "CANCELLED", null, now);
                continue;
            }
            List<PageRef> pages = jdbc.sql("""
                            SELECT storage_key, content_type FROM receipt.receipt_page
                            WHERE receipt_id = :receipt AND household_id = :household ORDER BY position
                            """)
                    .param("receipt", receipt).param("household", household)
                    .query((rs, n) -> new PageRef(rs.getString("storage_key"), rs.getString("content_type")))
                    .list();
            claims.add(new Claim(jobId, receipt, new HouseholdId(household), new UserId(uploader.get(0)),
                    ((Number) job.get("attempt_count")).intValue(), pages));
        }
        return claims;
    }

    @Override
    public boolean succeed(Claim claim, Attempt attempt, ReceiptDraft draft, Instant now) {
        if (!lockReceipt(claim)) {
            return false; // deleted meanwhile: its jobs went with it, the result is discarded
        }
        UUID extraction = insertAttempt(claim, attempt, now);
        int updated = jdbc.sql("""
                        UPDATE receipt.receipt
                        SET analysis_status = 'SUCCEEDED', analysis_failure_reason = NULL, ai_processed = true,
                            updated_at = :now, version = version + 1
                        WHERE id = :receipt AND household_id = :household AND status = 'OPEN'
                          AND analysis_status = 'RUNNING'
                        """)
                .param("now", utc(now)).param("receipt", claim.receiptId())
                .param("household", claim.householdId().value())
                .update();
        if (updated == 0) {
            finishJob(claim.jobId(), "CANCELLED", null, now);
            return false;
        }
        writeDraft(claim, extraction, draft, now);
        finishJob(claim.jobId(), "SUCCEEDED", null, now);
        return true;
    }

    @Override
    public void fail(Claim claim, @Nullable Attempt attempt, String errorCode, @Nullable Instant retryAt,
            String failureReason, boolean processed, Instant now) {
        if (!lockReceipt(claim)) {
            return;
        }
        if (attempt != null) {
            insertAttempt(claim, attempt, now);
        }
        int attempts = Math.min(3, claim.attemptCount() + 1);
        String receiptStatus = retryAt != null ? "PENDING" : "FAILED";
        int updated = jdbc.sql("""
                        UPDATE receipt.receipt
                        SET analysis_status = :status, analysis_failure_reason = :reason,
                            ai_processed = ai_processed OR :processed, updated_at = :now
                        WHERE id = :receipt AND household_id = :household AND status = 'OPEN'
                          AND analysis_status = 'RUNNING'
                        """)
                .param("status", receiptStatus)
                .param("reason", retryAt != null ? null : failureReason)
                .param("processed", processed)
                .param("now", utc(now)).param("receipt", claim.receiptId())
                .param("household", claim.householdId().value())
                .update();
        if (updated == 0) {
            finishJob(claim.jobId(), "CANCELLED", errorCode, now);
            return;
        }
        if (retryAt != null) {
            jdbc.sql("""
                            UPDATE receipt.receipt_analysis_job
                            SET status = 'PENDING', attempt_count = :attempts, next_attempt_at = :retryAt,
                                locked_by = NULL, locked_until = NULL, last_error_code = :error, updated_at = :now
                            WHERE id = :job
                            """)
                    .param("attempts", attempts).param("retryAt", utc(retryAt)).param("error", errorCode)
                    .param("now", utc(now)).param("job", claim.jobId())
                    .update();
        } else {
            jdbc.sql("""
                            UPDATE receipt.receipt_analysis_job
                            SET status = 'FAILED', attempt_count = :attempts, locked_by = NULL, locked_until = NULL,
                                last_error_code = :error, updated_at = :now
                            WHERE id = :job
                            """)
                    .param("attempts", attempts).param("error", errorCode).param("now", utc(now))
                    .param("job", claim.jobId())
                    .update();
        }
    }

    @Override
    public void cancel(Claim claim, Instant now) {
        jdbc.sql("""
                        UPDATE receipt.receipt SET analysis_status = 'CANCELLED', updated_at = :now
                        WHERE id = :receipt AND household_id = :household AND status = 'OPEN'
                          AND analysis_status = 'RUNNING'
                        """)
                .param("now", utc(now)).param("receipt", claim.receiptId())
                .param("household", claim.householdId().value())
                .update();
        finishJob(claim.jobId(), "CANCELLED", "CONSENT_MISSING", now);
    }

    @Override
    public void request(UUID receiptId, HouseholdId household, Trigger trigger, boolean manualRetry, Instant now) {
        jdbc.sql("""
                        UPDATE receipt.receipt
                        SET analysis_status = 'PENDING', analysis_failure_reason = NULL,
                            manual_retry_count = manual_retry_count + :increment, updated_at = :now,
                            version = version + 1
                        WHERE id = :receipt AND household_id = :household AND status = 'OPEN'
                        """)
                .param("increment", manualRetry ? 1 : 0).param("now", utc(now)).param("receipt", receiptId)
                .param("household", household.value())
                .update();
        enqueue(receiptId, household, trigger, now);
    }

    @Override
    public int cancelPendingOf(UserId uploader, Instant now) {
        return jdbc.sql("""
                        WITH cancelled AS (
                            UPDATE receipt.receipt_analysis_job j
                            SET status = 'CANCELLED', last_error_code = 'CONSENT_WITHDRAWN', updated_at = :now
                            FROM receipt.receipt r
                            WHERE j.receipt_id = r.id AND j.household_id = r.household_id AND j.status = 'PENDING'
                              AND r.uploaded_by = :uploader
                            RETURNING j.receipt_id, j.household_id)
                        UPDATE receipt.receipt r SET analysis_status = 'CANCELLED', updated_at = :now
                        FROM cancelled c
                        WHERE r.id = c.receipt_id AND r.household_id = c.household_id AND r.status = 'OPEN'
                          AND r.analysis_status = 'PENDING'
                        """)
                .param("now", utc(now)).param("uploader", uploader.value())
                .update();
    }

    /** Locks the receipt row so it cannot be deleted while a result is applied; {@code false} when it is gone. */
    private boolean lockReceipt(Claim claim) {
        return !jdbc.sql("SELECT 1 FROM receipt.receipt WHERE id = :receipt AND household_id = :household FOR UPDATE")
                .param("receipt", claim.receiptId()).param("household", claim.householdId().value())
                .query(Integer.class).list().isEmpty();
    }

    private UUID insertAttempt(Claim claim, Attempt attempt, Instant now) {
        UUID id = UuidV7.generate(clock);
        jdbc.sql("""
                        INSERT INTO receipt.receipt_extraction
                            (id, receipt_id, household_id, job_id, attempt_no, provider, model, prompt_version,
                             outcome, raw_output, raw_purge_at, latency_ms, created_at)
                        SELECT :id, :receipt, :household, :job,
                               coalesce((SELECT max(attempt_no) FROM receipt.receipt_extraction
                                         WHERE receipt_id = :receipt), 0) + 1,
                               :provider, :model, :promptVersion, :outcome,
                               CASE WHEN CAST(:raw AS text) IS NULL THEN NULL ELSE to_jsonb(CAST(:raw AS text)) END,
                               :purgeAt, :latency, :now
                        """)
                .param("id", id).param("receipt", claim.receiptId()).param("household", claim.householdId().value())
                .param("job", claim.jobId()).param("provider", attempt.provider()).param("model", attempt.model())
                .param("promptVersion", attempt.promptVersion()).param("outcome", attempt.outcome())
                .param("raw", attempt.rawOutput())
                .param("purgeAt", utc(now.plus(RAW_RETENTION_DAYS, ChronoUnit.DAYS)))
                .param("latency", (int) Math.min(Integer.MAX_VALUE, Math.max(0, attempt.latencyMs())))
                .param("now", utc(now))
                .update();
        return id;
    }

    private void writeDraft(Claim claim, UUID extraction, ReceiptDraft draft, Instant now) {
        jdbc.sql("DELETE FROM receipt.receipt_draft WHERE receipt_id = :receipt AND household_id = :household")
                .param("receipt", claim.receiptId()).param("household", claim.householdId().value())
                .update();
        Map<String, Object> fields = new LinkedHashMap<>();
        draft.fields().forEach((name, field) -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("raw", field.raw());
            value.put("candidates", field.candidates());
            value.put("status", field.status());
            value.put("confidence", field.confidence());
            fields.put(name, value);
        });
        fields.put("findings", List.copyOf(draft.findings()));
        jdbc.sql("""
                        INSERT INTO receipt.receipt_draft
                            (receipt_id, household_id, extraction_id, document_type, tax_mode, proposed_kind,
                             merchant_key, total_minor, purchase_date, detected_currency, fields, proposed_items,
                             duplicate_candidates, created_at, updated_at, version)
                        VALUES (:receipt, :household, :extraction, :documentType, :taxMode, :kind, :merchantKey,
                                :total, :date, :currency, CAST(:fields AS jsonb), '[]'::jsonb, '[]'::jsonb, :now, :now,
                                0)
                        """)
                .param("receipt", claim.receiptId()).param("household", claim.householdId().value())
                .param("extraction", extraction)
                .param("documentType", draft.documentType().name()).param("taxMode", draft.taxMode().name())
                .param("kind", draft.proposedKind().name())
                .param("merchantKey", draft.merchantKey().orElse(null))
                .param("total", draft.totalMinor().orElse(null))
                .param("date", draft.purchaseDate().orElse(null))
                .param("currency", draft.detectedCurrency().orElse(null))
                .param("fields", json.writeValueAsString(fields))
                .param("now", utc(now))
                .update();
        for (ReceiptDraft.Line line : draft.lines()) {
            jdbc.sql("""
                            INSERT INTO receipt.receipt_line_item
                                (id, receipt_id, household_id, position, type, description, quantity_raw, amount_raw,
                                 amount_minor, amount_status)
                            VALUES (:id, :receipt, :household, :position, :type, :description, :quantity, :raw,
                                    :minor, :status)
                            """)
                    .param("id", UuidV7.generate(clock)).param("receipt", claim.receiptId())
                    .param("household", claim.householdId().value()).param("position", line.position())
                    .param("type", line.type().name()).param("description", line.description())
                    .param("quantity", line.quantityRaw()).param("raw", line.amountRaw())
                    .param("minor", line.amountMinor()).param("status", line.amountStatus())
                    .update();
        }
    }

    private void finishJob(UUID job, String status, @Nullable String error, Instant now) {
        jdbc.sql("""
                        UPDATE receipt.receipt_analysis_job
                        SET status = :status, locked_by = NULL, locked_until = NULL,
                            last_error_code = coalesce(:error, last_error_code), updated_at = :now
                        WHERE id = :job
                        """)
                .param("status", status).param("error", error).param("now", utc(now)).param("job", job)
                .update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
