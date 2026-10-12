package com.couplefinance.receipt.web;

import java.net.URI;
import java.time.Instant;

import com.couplefinance.receipt.application.ReceiptVaultService;
import io.swagger.v3.oas.annotations.media.Schema;

/** A short-lived read URL of one receipt page. Never cache, log or share it. */
@Schema(name = "ReceiptPageUrl", requiredProperties = {"url", "expiresAt", "contentType"})
record ReceiptPageUrlResponse(
        @Schema(description = "Presigned GET URL, valid 5 minutes.") URI url,
        Instant expiresAt,
        String contentType) {

    static ReceiptPageUrlResponse from(ReceiptVaultService.PageUrl url) {
        return new ReceiptPageUrlResponse(url.url(), url.expiresAt(), url.contentType());
    }
}
