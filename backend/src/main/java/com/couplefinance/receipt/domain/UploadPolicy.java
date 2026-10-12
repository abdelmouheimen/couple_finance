package com.couplefinance.receipt.domain;

import java.util.ArrayList;
import java.util.List;

import com.couplefinance.shared.error.ApplicationException;

/**
 * BR-RCP-01 limits, checked before any byte is scanned, decoded or stored. Formats come from the file bytes
 * ({@link DocumentFormat#detect}). HEIC is recognised but refused with a specific message: the server has no safe
 * HEIC decoder, and the mobile app always sends JPEG (images are compressed client-side, F4).
 */
public final class UploadPolicy {

    public static final int MAX_IMAGES = 5;
    public static final int MAX_PDF_PAGES = 5;
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 25L * 1024 * 1024;

    private UploadPolicy() {
    }

    /** A file as received: its bytes only (the client's name and declared type are not trusted). */
    public record UploadedFile(byte[] bytes) {
    }

    /** A file accepted by the policy, with the format detected from its bytes. */
    public record CheckedFile(byte[] bytes, DocumentFormat format) {
    }

    /** The accepted files and what they form. */
    public record Checked(SourceKind kind, List<CheckedFile> files, long totalBytes) {
        public Checked {
            files = List.copyOf(files);
        }
    }

    public static Checked check(List<UploadedFile> files) {
        if (files == null || files.isEmpty() || files.size() > MAX_IMAGES) {
            throw new ApplicationException(ReceiptErrorCode.RECEIPT_FILE_COUNT_INVALID,
                    "A receipt is 1 to " + MAX_IMAGES + " images or one PDF.");
        }
        long total = 0;
        List<CheckedFile> checked = new ArrayList<>();
        for (UploadedFile file : files) {
            byte[] bytes = file.bytes();
            if (bytes.length > MAX_FILE_BYTES) {
                throw new ApplicationException(ReceiptErrorCode.RECEIPT_FILE_TOO_LARGE, "Each file is at most 10 MB.");
            }
            total += bytes.length;
            DocumentFormat format = DocumentFormat.detect(bytes)
                    .orElseThrow(() -> new ApplicationException(ReceiptErrorCode.RECEIPT_FORMAT_UNSUPPORTED,
                            "Only JPEG, PNG and PDF files are accepted."));
            if (format == DocumentFormat.HEIC) {
                throw new ApplicationException(ReceiptErrorCode.RECEIPT_FORMAT_UNSUPPORTED,
                        "HEIC images must be converted to JPEG before upload.");
            }
            checked.add(new CheckedFile(bytes, format));
        }
        if (total > MAX_TOTAL_BYTES) {
            throw new ApplicationException(ReceiptErrorCode.RECEIPT_TOO_LARGE, "The files total at most 25 MB.");
        }
        long pdfs = checked.stream().filter(f -> f.format() == DocumentFormat.PDF).count();
        if (pdfs > 0 && checked.size() > 1) {
            throw new ApplicationException(pdfs == checked.size() ? ReceiptErrorCode.RECEIPT_FILE_COUNT_INVALID
                    : ReceiptErrorCode.RECEIPT_FORMAT_MIXED, "A PDF receipt is a single file.");
        }
        return new Checked(pdfs == 1 ? SourceKind.PDF : SourceKind.IMAGES, checked, total);
    }
}
