package com.couplefinance.receipt.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.couplefinance.receipt.domain.AnalysisStatus;
import com.couplefinance.receipt.domain.Receipt;
import com.couplefinance.receipt.domain.ReceiptPage;
import com.couplefinance.receipt.domain.ReceiptRepository;
import com.couplefinance.receipt.domain.ReceiptStatus;
import com.couplefinance.receipt.domain.SourceKind;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC adapter of {@link ReceiptRepository} (database-schema.md sections 8.2, 8.3). Every query is scoped by
 * {@code household_id} and by visibility ({@code uploaded_by} for drafts, BR-RCP-16).
 */
@Repository
class JdbcReceiptRepository implements ReceiptRepository {

    private static final String VISIBLE = """
            r.household_id = :householdId AND r.status = 'OPEN' AND r.uploaded_by = :viewer
            """;

    private final JdbcClient jdbc;

    JdbcReceiptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Receipt receipt) {
        jdbc.sql("""
                        INSERT INTO receipt.receipt
                            (id, household_id, uploaded_by, source_kind, page_count, total_size_bytes, status,
                             analysis_status, created_at, updated_at, version)
                        VALUES (:id, :householdId, :uploadedBy, :sourceKind, :pageCount, :totalSize, :status,
                                :analysisStatus, :createdAt, :updatedAt, :version)
                        """)
                .param("id", receipt.id())
                .param("householdId", receipt.householdId().value())
                .param("uploadedBy", receipt.uploadedBy().value())
                .param("sourceKind", receipt.sourceKind().name())
                .param("pageCount", receipt.pageCount())
                .param("totalSize", receipt.totalSizeBytes())
                .param("status", receipt.status().name())
                .param("analysisStatus", receipt.analysisStatus().name())
                .param("createdAt", utc(receipt.createdAt()))
                .param("updatedAt", utc(receipt.updatedAt()))
                .param("version", receipt.version())
                .update();
        for (ReceiptPage page : receipt.pages()) {
            jdbc.sql("""
                            INSERT INTO receipt.receipt_page
                                (id, receipt_id, household_id, position, storage_key, content_type, size_bytes,
                                 width_px, height_px, original_sha256, perceptual_hash, created_at)
                            VALUES (:id, :receiptId, :householdId, :position, :storageKey, :contentType, :size,
                                    :width, :height, :sha, :phash, :createdAt)
                            """)
                    .param("id", page.id())
                    .param("receiptId", receipt.id())
                    .param("householdId", receipt.householdId().value())
                    .param("position", page.position())
                    .param("storageKey", page.storageKey())
                    .param("contentType", page.contentType())
                    .param("size", page.sizeBytes())
                    .param("width", page.widthPx())
                    .param("height", page.heightPx())
                    .param("sha", page.originalSha256())
                    .param("phash", page.perceptualHash())
                    .param("createdAt", utc(receipt.createdAt()))
                    .update();
        }
    }

    @Override
    public Optional<Receipt> findVisible(UUID id, HouseholdId household, UserId viewer) {
        return one(id, household, viewer, false);
    }

    @Override
    public Optional<Receipt> lockVisible(UUID id, HouseholdId household, UserId viewer) {
        return one(id, household, viewer, true);
    }

    @Override
    public List<Receipt> listVisible(HouseholdId household, UserId viewer, @Nullable Instant afterCreatedAt,
            @Nullable UUID afterId, int limit) {
        String after = afterCreatedAt == null ? ""
                : " AND (r.created_at, r.id) < (:afterCreatedAt, :afterId)";
        var statement = jdbc.sql("SELECT r.* FROM receipt.receipt r WHERE " + VISIBLE + after
                        + " ORDER BY r.created_at DESC, r.id DESC LIMIT :limit")
                .param("householdId", household.value())
                .param("viewer", viewer.value())
                .param("limit", limit);
        if (afterCreatedAt != null) {
            statement = statement.param("afterCreatedAt", utc(afterCreatedAt)).param("afterId", afterId);
        }
        List<Row> rows = statement.query(JdbcReceiptRepository::row).list();
        return withPages(household, rows);
    }

    @Override
    public void delete(UUID id, HouseholdId household) {
        jdbc.sql("DELETE FROM receipt.receipt WHERE id = :id AND household_id = :householdId")
                .param("id", id)
                .param("householdId", household.value())
                .update();
    }

    private Optional<Receipt> one(UUID id, HouseholdId household, UserId viewer, boolean lock) {
        List<Row> rows = jdbc.sql("SELECT r.* FROM receipt.receipt r WHERE r.id = :id AND " + VISIBLE
                        + (lock ? " FOR UPDATE" : ""))
                .param("id", id)
                .param("householdId", household.value())
                .param("viewer", viewer.value())
                .query(JdbcReceiptRepository::row).list();
        return withPages(household, rows).stream().findFirst();
    }

    private List<Receipt> withPages(HouseholdId household, List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<ReceiptPage>> pages = new LinkedHashMap<>();
        rows.forEach(row -> pages.put(row.id(), new ArrayList<>()));
        jdbc.sql("""
                        SELECT * FROM receipt.receipt_page
                        WHERE household_id = :householdId AND receipt_id IN (:ids)
                        ORDER BY receipt_id, position
                        """)
                .param("householdId", household.value())
                .param("ids", List.copyOf(pages.keySet()))
                .query((rs, n) -> Map.entry(rs.getObject("receipt_id", UUID.class), page(rs)))
                .list()
                .forEach(entry -> pages.get(entry.getKey()).add(entry.getValue()));
        return rows.stream().map(row -> row.toReceipt(pages.get(row.id()))).toList();
    }

    private record Row(UUID id, UUID householdId, UUID uploadedBy, String sourceKind, int totalSize, String status,
            String analysisStatus, Instant createdAt, Instant updatedAt, long version) {
        Receipt toReceipt(List<ReceiptPage> pages) {
            return new Receipt(id, new HouseholdId(householdId), new UserId(uploadedBy),
                    SourceKind.valueOf(sourceKind), totalSize, ReceiptStatus.valueOf(status),
                    AnalysisStatus.valueOf(analysisStatus), createdAt, updatedAt, version, pages);
        }
    }

    private static Row row(ResultSet rs, int rowNum) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("household_id", UUID.class),
                rs.getObject("uploaded_by", UUID.class), rs.getString("source_kind"), rs.getInt("total_size_bytes"),
                rs.getString("status"), rs.getString("analysis_status"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(), rs.getLong("version"));
    }

    private static ReceiptPage page(ResultSet rs) throws SQLException {
        long phash = rs.getLong("perceptual_hash");
        return new ReceiptPage(rs.getObject("id", UUID.class), rs.getInt("position"), rs.getString("storage_key"),
                rs.getString("content_type"), rs.getInt("size_bytes"), rs.getInt("width_px"),
                rs.getInt("height_px"), rs.getBytes("original_sha256"), rs.wasNull() ? null : phash);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
