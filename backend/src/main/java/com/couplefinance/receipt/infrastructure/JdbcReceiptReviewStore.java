package com.couplefinance.receipt.infrastructure;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.couplefinance.receipt.domain.DocumentType;
import com.couplefinance.receipt.domain.LineItemType;
import com.couplefinance.receipt.domain.ProposedKind;
import com.couplefinance.receipt.domain.Receipt;
import com.couplefinance.receipt.domain.ReceiptDraft;
import com.couplefinance.receipt.domain.ReceiptPage;
import com.couplefinance.receipt.domain.ReceiptReviewStore;
import com.couplefinance.receipt.domain.ReceiptStatus;
import com.couplefinance.receipt.domain.TaxMode;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** JDBC adapter of {@link ReceiptReviewStore}; every statement is scoped by {@code household_id}. */
@Repository
class JdbcReceiptReviewStore implements ReceiptReviewStore {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcReceiptReviewStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<ReceiptDraft> draft(UUID receiptId, HouseholdId household) {
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT document_type, tax_mode, proposed_kind, merchant_key, total_minor, purchase_date,
                               detected_currency, fields::text AS fields
                        FROM receipt.receipt_draft WHERE receipt_id = :receipt AND household_id = :household
                        """)
                .param("receipt", receiptId).param("household", household.value())
                .query().listOfRows();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        JsonNode stored = json.readTree((String) row.get("fields"));
        Map<String, ReceiptDraft.Field> fields = new LinkedHashMap<>();
        Set<String> findings = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> entry : stored.properties()) {
            if ("findings".equals(entry.getKey())) {
                entry.getValue().forEach(finding -> findings.add(finding.asString()));
                continue;
            }
            JsonNode field = entry.getValue();
            List<String> candidates = new ArrayList<>();
            field.path("candidates").forEach(candidate -> candidates.add(candidate.asString()));
            fields.put(entry.getKey(), new ReceiptDraft.Field(
                    field.hasNonNull("raw") ? field.get("raw").asString() : null, candidates,
                    field.path("status").asString(ReceiptDraft.MISSING),
                    field.hasNonNull("confidence") ? field.get("confidence").decimalValue() : null));
        }
        List<ReceiptDraft.Line> lines = jdbc.sql("""
                        SELECT position, type, description, quantity_raw, amount_raw, amount_minor, amount_status
                        FROM receipt.receipt_line_item
                        WHERE receipt_id = :receipt AND household_id = :household ORDER BY position
                        """)
                .param("receipt", receiptId).param("household", household.value())
                .query((rs, n) -> {
                    long minor = rs.getLong("amount_minor");
                    Long amount = rs.wasNull() ? null : minor;
                    return new ReceiptDraft.Line(rs.getInt("position"), LineItemType.valueOf(rs.getString("type")),
                            rs.getString("description"), rs.getString("quantity_raw"), rs.getString("amount_raw"),
                            amount, rs.getString("amount_status"));
                }).list();
        Object date = row.get("purchase_date");
        Object currency = row.get("detected_currency");
        return Optional.of(new ReceiptDraft(DocumentType.valueOf((String) row.get("document_type")),
                TaxMode.valueOf((String) row.get("tax_mode")), ProposedKind.valueOf((String) row.get("proposed_kind")),
                Optional.ofNullable((String) row.get("merchant_key")),
                Optional.ofNullable((Number) row.get("total_minor")).map(Number::longValue),
                Optional.ofNullable(date).map(d -> d instanceof Date sql ? sql.toLocalDate() : (LocalDate) d),
                Optional.ofNullable(currency).map(c -> c.toString().strip()), fields, lines, findings));
    }

    @Override
    public List<RelatedReceipt> sameFile(Receipt receipt) {
        Map<UUID, RelatedReceipt> related = new LinkedHashMap<>();
        for (ReceiptPage page : receipt.pages()) {
            jdbc.sql("""
                            SELECT DISTINCT r.id, r.status, r.uploaded_by
                            FROM receipt.receipt_page p
                            JOIN receipt.receipt r ON r.id = p.receipt_id AND r.household_id = p.household_id
                            WHERE p.household_id = :household AND p.original_sha256 = :sha
                              AND p.receipt_id <> :receipt AND r.status <> 'ABANDONED'
                            """)
                    .param("household", receipt.householdId().value()).param("sha", page.originalSha256())
                    .param("receipt", receipt.id())
                    .query(JdbcReceiptReviewStore::related).list()
                    .forEach(r -> related.putIfAbsent(r.id(), r));
        }
        return List.copyOf(related.values());
    }

    @Override
    public List<RelatedReceipt> similarImage(Receipt receipt, int maxDistance) {
        Map<UUID, RelatedReceipt> related = new LinkedHashMap<>();
        for (ReceiptPage page : receipt.pages()) {
            if (page.perceptualHash() == null) {
                continue;
            }
            // Hamming distance cannot use a B-tree: bounded scan of the household's pages (section 8.9).
            jdbc.sql("""
                            SELECT DISTINCT r.id, r.status, r.uploaded_by
                            FROM receipt.receipt_page p
                            JOIN receipt.receipt r ON r.id = p.receipt_id AND r.household_id = p.household_id
                            WHERE p.household_id = :household AND p.receipt_id <> :receipt
                              AND r.status <> 'ABANDONED' AND p.perceptual_hash IS NOT NULL
                              AND bit_count((p.perceptual_hash # CAST(:hash AS bigint))::bit(64)) <= :max
                            """)
                    .param("household", receipt.householdId().value()).param("receipt", receipt.id())
                    .param("hash", page.perceptualHash()).param("max", maxDistance)
                    .query(JdbcReceiptReviewStore::related).list()
                    .forEach(r -> related.putIfAbsent(r.id(), r));
        }
        return List.copyOf(related.values());
    }

    @Override
    public boolean link(UUID receiptId, HouseholdId household, ReceiptStatus status, long reviewedVersion,
            Instant now) {
        int updated = jdbc.sql("""
                        UPDATE receipt.receipt
                        SET status = :status, linked_at = :now, updated_at = :now, version = version + 1,
                            analysis_status = CASE WHEN analysis_status IN ('PENDING', 'RUNNING') THEN 'CANCELLED'
                                                   ELSE analysis_status END
                        WHERE id = :receipt AND household_id = :household AND status = 'OPEN'
                          AND version = :version
                        """)
                .param("status", status.name()).param("now", utc(now)).param("receipt", receiptId)
                .param("household", household.value()).param("version", reviewedVersion)
                .update();
        if (updated == 0) {
            return false;
        }
        jdbc.sql("""
                        UPDATE receipt.receipt_analysis_job
                        SET status = 'CANCELLED', locked_by = NULL, locked_until = NULL,
                            last_error_code = 'RECEIPT_LINKED', updated_at = :now
                        WHERE receipt_id = :receipt AND household_id = :household
                          AND status IN ('PENDING', 'RUNNING')
                        """)
                .param("now", utc(now)).param("receipt", receiptId).param("household", household.value())
                .update();
        return true;
    }

    @Override
    public void record(Confirmation confirmation) {
        jdbc.sql("""
                        INSERT INTO receipt.receipt_confirmation
                            (receipt_id, household_id, mode, confirmed_by, confirmed_at, acknowledged_total_minor,
                             acknowledged_date, resolutions, idempotency_key)
                        VALUES (:receipt, :household, :mode, :by, :at, :total, :date, CAST(:resolutions AS jsonb),
                                :key)
                        """)
                .param("receipt", confirmation.receiptId()).param("household", confirmation.household().value())
                .param("mode", confirmation.mode()).param("by", confirmation.confirmedBy().value())
                .param("at", utc(confirmation.confirmedAt()))
                .param("total", confirmation.acknowledgedTotalMinor())
                .param("date", confirmation.acknowledgedDate())
                .param("resolutions", json.writeValueAsString(confirmation.resolutions()))
                .param("key", confirmation.idempotencyKey())
                .update();
    }

    private static RelatedReceipt related(ResultSet rs, int rowNum) throws SQLException {
        return new RelatedReceipt(rs.getObject("id", UUID.class), ReceiptStatus.valueOf(rs.getString("status")),
                new UserId(rs.getObject("uploaded_by", UUID.class)));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
