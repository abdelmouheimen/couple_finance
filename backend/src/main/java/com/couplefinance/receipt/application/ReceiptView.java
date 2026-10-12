package com.couplefinance.receipt.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.couplefinance.receipt.domain.Receipt;
import com.couplefinance.receipt.domain.ReceiptPage;
import com.couplefinance.receipt.domain.ReceiptState;
import com.couplefinance.receipt.domain.SourceKind;
import org.jspecify.annotations.Nullable;

/** Read model of a receipt for the web layer: metadata only, never storage keys or URLs. */
public record ReceiptView(UUID id, SourceKind sourceKind, ReceiptState state, @Nullable String analysisFailureReason,
        int manualRetriesLeft, int totalSizeBytes, List<PageView> pages, Instant createdAt, long version,
        @Nullable UUID expenseId) {

    public record PageView(int position, String contentType, int sizeBytes, int widthPx, int heightPx) {
        static PageView from(ReceiptPage page) {
            return new PageView(page.position(), page.contentType(), page.sizeBytes(), page.widthPx(),
                    page.heightPx());
        }
    }

    public static ReceiptView from(Receipt receipt) {
        return from(receipt, null);
    }

    /** @param expenseId the expense this receipt is evidence of, when linked and visible to the viewer */
    public static ReceiptView from(Receipt receipt, @Nullable UUID expenseId) {
        return new ReceiptView(receipt.id(), receipt.sourceKind(),
                ReceiptState.of(receipt.status(), receipt.analysisStatus()), receipt.analysisFailureReason(),
                Math.max(0, Receipt.MAX_MANUAL_RETRIES - receipt.manualRetryCount()), receipt.totalSizeBytes(),
                receipt.pages().stream().map(PageView::from).toList(), receipt.createdAt(), receipt.version(), expenseId);
    }
}
