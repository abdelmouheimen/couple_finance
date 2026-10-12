package com.couplefinance.receipt.domain;

import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * One stored page of a receipt (database-schema.md section 8.3): a re-encoded image in private storage under a
 * random key. Immutable.
 *
 * @param originalSha256 SHA-256 of the uploaded file bytes this page came from (all pages of a PDF share it)
 * @param perceptualHash 64-bit perceptual hash of the normalised image, {@code null} if not computable
 */
public record ReceiptPage(UUID id, int position, String storageKey, String contentType, int sizeBytes, int widthPx,
        int heightPx, byte[] originalSha256, @Nullable Long perceptualHash) {

    public ReceiptPage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(storageKey, "storageKey");
        Objects.requireNonNull(contentType, "contentType");
        if (position < 1 || position > UploadPolicy.MAX_PDF_PAGES) {
            throw new IllegalArgumentException("position must be between 1 and 5");
        }
        if (sizeBytes < 1 || sizeBytes > UploadPolicy.MAX_FILE_BYTES) {
            throw new IllegalArgumentException("sizeBytes out of range");
        }
        if (widthPx < 1 || heightPx < 1) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        if (originalSha256 == null || originalSha256.length != 32) {
            throw new IllegalArgumentException("originalSha256 must be 32 bytes");
        }
        originalSha256 = originalSha256.clone();
    }

    @Override
    public byte[] originalSha256() {
        return originalSha256.clone();
    }
}
