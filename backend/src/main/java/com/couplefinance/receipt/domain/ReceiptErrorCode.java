package com.couplefinance.receipt.domain;

import com.couplefinance.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** Stable error codes of the receipt module. */
public enum ReceiptErrorCode implements ErrorCode {

    /** BR-RCP-01: 1 to 5 images, or exactly one PDF. */
    RECEIPT_FILE_COUNT_INVALID(HttpStatus.BAD_REQUEST),
    /** BR-RCP-01: images and a PDF cannot be mixed in one receipt. */
    RECEIPT_FORMAT_MIXED(HttpStatus.BAD_REQUEST),
    /** BR-RCP-01: the bytes are not a supported JPEG, PNG or PDF (HEIC is converted to JPEG by the app). */
    RECEIPT_FORMAT_UNSUPPORTED(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    /** BR-RCP-01: a file exceeds 10 MB. */
    RECEIPT_FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE),
    /** BR-RCP-01: the files exceed 25 MB in total. */
    RECEIPT_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE),
    /** BR-RCP-01: a PDF has 1 to 5 pages. */
    RECEIPT_PDF_PAGE_COUNT_INVALID(HttpStatus.BAD_REQUEST),
    /** The file has a supported signature but cannot be decoded safely (corrupt, encrypted, too many pixels). */
    RECEIPT_FILE_UNREADABLE(HttpStatus.BAD_REQUEST),
    /** The malware scanner reported a threat; nothing was stored. */
    RECEIPT_FILE_REJECTED(HttpStatus.UNPROCESSABLE_CONTENT),
    /** The malware scanner cannot be reached: uploads are refused (fail-closed) and nothing is stored. */
    RECEIPT_SCAN_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    /** Unknown, of another household, or a draft of the partner (BR-RCP-16): indistinguishable (404). */
    RECEIPT_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** The receipt has no page at this position. */
    RECEIPT_PAGE_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** BR-AI-05: analysis needs the uploader's receipt-analysis consent. */
    AI_CONSENT_REQUIRED(HttpStatus.CONFLICT),
    /** BR-RCP-17: the receipt is linked, queued, running or already analysed. */
    RECEIPT_ANALYSIS_NOT_ALLOWED(HttpStatus.CONFLICT),
    /** BR-RCP-17: a failed analysis can be re-run at most three times. */
    RECEIPT_ANALYSIS_RETRY_LIMIT(HttpStatus.CONFLICT),
    /** Only an open receipt can be deleted from the vault; a linked one is removed from its expense. */
    RECEIPT_NOT_DELETABLE(HttpStatus.CONFLICT);

    private final HttpStatus status;

    ReceiptErrorCode(HttpStatus status) {
        this.status = status;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
