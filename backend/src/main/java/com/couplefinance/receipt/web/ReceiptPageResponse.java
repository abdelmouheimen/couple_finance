package com.couplefinance.receipt.web;

import java.util.List;

import com.couplefinance.receipt.application.ReceiptView;
import com.couplefinance.shared.pagination.CursorPage;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** A page of the receipt vault, newest first. */
@Schema(name = "ReceiptList", requiredProperties = {"items"})
record ReceiptPageResponse(
        List<ReceiptResponse> items,
        @Schema(description = "Opaque cursor of the next page, absent on the last page.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable String nextCursor) {

    static ReceiptPageResponse from(CursorPage<ReceiptView> page) {
        return new ReceiptPageResponse(page.items().stream().map(ReceiptResponse::from).toList(), page.nextCursor());
    }
}
