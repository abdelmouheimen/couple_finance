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
import org.jspecify.annotations.Nullable;

/**
 * Receipt evidence (domain-model.md, database-schema.md section 8.2): the stored pages of what a member uploaded.
 * A receipt is evidence, never money (ADR-006); it holds no amount until a draft exists. Framework-free: persisted
 * through the {@link ReceiptRepository} port.
 */
public record Receipt(UUID id, HouseholdId householdId, UserId uploadedBy, SourceKind sourceKind, int totalSizeBytes,
        ReceiptStatus status, AnalysisStatus analysisStatus, @Nullable String analysisFailureReason,
        int manualRetryCount, Instant createdAt, Instant updatedAt, long version, List<ReceiptPage> pages) {

    /** BR-RCP-17: a failed analysis can be re-run manually at most three times per receipt. */
    public static final int MAX_MANUAL_RETRIES = 3;

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
     * A freshly uploaded, open receipt. It is queued for analysis only when the uploader consented to receipt
     * analysis at upload time; otherwise it is {@link AnalysisStatus#SKIPPED_NO_CONSENT} and is never processed
     * retroactively (BR-AI-05, BR-RCP-17).
     */
    public static Receipt uploaded(UUID id, HouseholdId household, UserId uploader, SourceKind kind,
            long totalSizeBytes, List<ReceiptPage> pages, boolean uploaderConsented, Instant now) {
        return new Receipt(id, household, uploader, kind, Math.toIntExact(totalSizeBytes), ReceiptStatus.OPEN,
                uploaderConsented ? AnalysisStatus.PENDING : AnalysisStatus.SKIPPED_NO_CONSENT, null, 0, now, now, 0,
                pages);
    }

    /**
     * Guard of an explicit analysis request (BR-RCP-17): only an open receipt whose analysis failed (at most three
     * manual retries) or never ran for lack of consent / was cancelled can be (re-)analysed.
     *
     * @return whether the request is a manual retry of a failure (it then counts against the limit)
     */
    public boolean requireAnalysable() {
        if (status != ReceiptStatus.OPEN) {
            throw new ApplicationException(ReceiptErrorCode.RECEIPT_ANALYSIS_NOT_ALLOWED,
                    "Only an open receipt can be analysed.");
        }
        return switch (analysisStatus) {
            case FAILED -> {
                if (manualRetryCount >= MAX_MANUAL_RETRIES) {
                    throw new ApplicationException(ReceiptErrorCode.RECEIPT_ANALYSIS_RETRY_LIMIT,
                            "This receipt was already re-analysed three times.");
                }
                yield true;
            }
            case SKIPPED_NO_CONSENT, CANCELLED -> false;
            case PENDING, RUNNING, SUCCEEDED -> throw new ApplicationException(
                    ReceiptErrorCode.RECEIPT_ANALYSIS_NOT_ALLOWED, "This receipt is already analysed or queued.");
        };
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
