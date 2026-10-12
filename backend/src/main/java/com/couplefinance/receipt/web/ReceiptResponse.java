package com.couplefinance.receipt.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.couplefinance.receipt.application.ReceiptView;
import com.couplefinance.receipt.domain.ReceiptState;
import com.couplefinance.receipt.domain.SourceKind;
import io.swagger.v3.oas.annotations.media.Schema;

/** A receipt of the vault: metadata only (pages are read through short-lived URLs). */
@Schema(name = "Receipt",
        requiredProperties = {"id", "sourceKind", "state", "totalSizeBytes", "pages", "createdAt", "version"})
record ReceiptResponse(
        UUID id,
        @Schema(description = "IMAGES (1-5 photos) or PDF (rasterised into page images).") SourceKind sourceKind,
        @Schema(description = "Combined lifecycle state. UPLOADED: stored, not analysed.") ReceiptState state,
        @Schema(description = "Size of the uploaded files, in bytes.") int totalSizeBytes,
        List<Page> pages,
        Instant createdAt,
        long version) {

    @Schema(name = "ReceiptPage", requiredProperties = {"position", "contentType", "sizeBytes", "widthPx", "heightPx"})
    record Page(int position, String contentType, int sizeBytes, int widthPx, int heightPx) {
    }

    static ReceiptResponse from(ReceiptView view) {
        return new ReceiptResponse(view.id(), view.sourceKind(), view.state(), view.totalSizeBytes(),
                view.pages().stream().map(p -> new Page(p.position(), p.contentType(), p.sizeBytes(), p.widthPx(),
                        p.heightPx())).toList(),
                view.createdAt(), view.version());
    }
}
