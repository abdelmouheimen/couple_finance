package com.couplefinance.receipt.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;

/**
 * Receipt evidence (domain-model.md, database-schema.md section 8.2): the stored pages of what a member uploaded.
 * A receipt is evidence, never money (ADR-006); it holds no amount until a draft exists. Framework-free: persisted
 * through the {@link ReceiptRepository} port.
 */
public record Receipt(UUID id, HouseholdId householdId, UserId uploadedBy, SourceKind sourceKind, int totalSizeBytes,
        ReceiptStatus status, AnalysisStatus analysisStatus, Instant createdAt, Instant updatedAt, long version,
        List<ReceiptPage> pages) {

    public Receipt {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(householdId, "householdId");
        Objects.requireNonNull(uploadedBy, "uploadedBy");
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(analysisStatus, "analysisStatus");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        pages = pages.stream().sorted(Comparator.comparingInt(ReceiptPage::position)).toList();
        if (pages.isEmpty() || pages.size() > UploadPolicy.MAX_PDF_PAGES) {
            throw new IllegalArgumentException("a receipt has 1 to 5 pages");
        }
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).position() != i + 1) {
                throw new IllegalArgumentException("page positions must be 1..n");
            }
        }
        if (totalSizeBytes < 1 || totalSizeBytes > UploadPolicy.MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("totalSizeBytes out of range");
        }
    }

    /**
     * A freshly uploaded receipt: open and never sent to an AI provider. Until receipt analysis and its consent
     * exist (DOC-003) every upload is {@link AnalysisStatus#SKIPPED_NO_CONSENT}, so no content can reach a provider
     * retroactively (BR-RCP-17).
     */
    public static Receipt uploaded(UUID id, HouseholdId household, UserId uploader, SourceKind kind,
            long totalSizeBytes, List<ReceiptPage> pages, Instant now) {
        return new Receipt(id, household, uploader, kind, Math.toIntExact(totalSizeBytes), ReceiptStatus.OPEN,
                AnalysisStatus.SKIPPED_NO_CONSENT, now, now, 0, pages);
    }

    public int pageCount() {
        return pages.size();
    }

    public Optional<ReceiptPage> page(int position) {
        return pages.stream().filter(p -> p.position() == position).findFirst();
    }

    /** Whether the receipt is still a private draft of its uploader (BR-RCP-16). */
    public boolean isDraft() {
        return status == ReceiptStatus.OPEN;
    }

    /**
     * Guard of a vault deletion: only an open receipt may be deleted outright. A linked receipt is evidence of an
     * expense and is removed from that expense instead (BR-RCP-20).
     */
    public void requireDeletable() {
        if (status != ReceiptStatus.OPEN) {
            throw new ApplicationException(ReceiptErrorCode.RECEIPT_NOT_DELETABLE,
                    "Only an open receipt can be deleted.");
        }
    }
}
