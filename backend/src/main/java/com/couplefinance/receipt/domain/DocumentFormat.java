package com.couplefinance.receipt.domain;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

/**
 * Formats recognised in uploads, identified from the file bytes (BR-RCP-01: "content type is verified from file
 * bytes"); the declared content type and the file name are never trusted.
 */
public enum DocumentFormat {
    JPEG,
    PNG,
    HEIC,
    PDF;

    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] FTYP = {'f', 't', 'y', 'p'};
    private static final Set<String> HEIF_BRANDS =
            Set.of("heic", "heix", "hevc", "hevx", "mif1", "msf1", "heim", "heis");

    /** The format announced by the leading bytes, empty for anything else. */
    public static Optional<DocumentFormat> detect(byte[] bytes) {
        if (startsWith(bytes, 0, JPEG_MAGIC)) {
            return Optional.of(JPEG);
        }
        if (startsWith(bytes, 0, PNG_MAGIC)) {
            return Optional.of(PNG);
        }
        if (startsWith(bytes, 0, PDF_MAGIC)) {
            return Optional.of(PDF);
        }
        if (bytes.length >= 12 && startsWith(bytes, 4, FTYP)
                && HEIF_BRANDS.contains(new String(bytes, 8, 4, StandardCharsets.US_ASCII))) {
            return Optional.of(HEIC);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] bytes, int offset, byte[] prefix) {
        if (bytes.length < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
