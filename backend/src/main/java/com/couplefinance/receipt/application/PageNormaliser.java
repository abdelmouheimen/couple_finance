package com.couplefinance.receipt.application;

import java.util.List;

import com.couplefinance.receipt.domain.DocumentFormat;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: safe decoding and re-encoding of an uploaded file into stored page images (architecture.md
 * section 6.2: "verify types/sizes, re-encode"). Re-encoding drops every metadata block (EXIF, GPS, XMP, ICC
 * comments) and any active content; a PDF is rasterised page by page and never stored or served as PDF.
 * Implementations reject inputs that cannot be decoded within their pixel and page bounds with
 * {@code RECEIPT_FILE_UNREADABLE} and a PDF of more than five pages with {@code RECEIPT_PDF_PAGE_COUNT_INVALID}.
 */
public interface PageNormaliser {

    List<NormalisedPage> normalise(DocumentFormat format, byte[] content);

    /**
     * A re-encoded page.
     *
     * @param perceptualHash 64-bit perceptual hash, {@code null} when it cannot be computed
     */
    record NormalisedPage(byte[] content, String contentType, int widthPx, int heightPx,
            @Nullable Long perceptualHash) {
    }
}
