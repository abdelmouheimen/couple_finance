package com.couplefinance.receipt.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import com.couplefinance.receipt.ReceiptFiles;
import com.couplefinance.shared.error.ApplicationException;
import org.junit.jupiter.api.Test;

/** BR-RCP-01: formats from bytes, counts and size limits, checked before anything is decoded or stored. */
class UploadPolicyTest {

    private static UploadPolicy.UploadedFile file(byte[] bytes) {
        return new UploadPolicy.UploadedFile(bytes);
    }

    private static ReceiptErrorCode code(Runnable call) {
        try {
            call.run();
        } catch (ApplicationException e) {
            return (ReceiptErrorCode) e.errorCode();
        }
        throw new AssertionError("expected an ApplicationException");
    }

    @Test
    void BR_RCP_01_one_to_five_images_form_an_images_receipt() {
        byte[] jpeg = ReceiptFiles.jpeg(40, 60);
        var checked = UploadPolicy.check(Collections.nCopies(5, file(jpeg)));
        assertThat(checked.kind()).isEqualTo(SourceKind.IMAGES);
        assertThat(checked.files()).hasSize(5).allMatch(f -> f.format() == DocumentFormat.JPEG);
        assertThat(checked.totalBytes()).isEqualTo(5L * jpeg.length);
    }

    @Test
    void BR_RCP_01_a_single_pdf_forms_a_pdf_receipt() {
        assertThat(UploadPolicy.check(List.of(file(ReceiptFiles.pdf(2)))).kind()).isEqualTo(SourceKind.PDF);
    }

    @Test
    void BR_RCP_01_counts_are_enforced() {
        byte[] png = ReceiptFiles.png(10, 10);
        assertThat(code(() -> UploadPolicy.check(List.of()))).isEqualTo(ReceiptErrorCode.RECEIPT_FILE_COUNT_INVALID);
        assertThat(code(() -> UploadPolicy.check(null))).isEqualTo(ReceiptErrorCode.RECEIPT_FILE_COUNT_INVALID);
        assertThat(code(() -> UploadPolicy.check(Collections.nCopies(6, file(png)))))
                .isEqualTo(ReceiptErrorCode.RECEIPT_FILE_COUNT_INVALID);
        byte[] pdf = ReceiptFiles.pdf(1);
        assertThat(code(() -> UploadPolicy.check(List.of(file(pdf), file(pdf)))))
                .isEqualTo(ReceiptErrorCode.RECEIPT_FILE_COUNT_INVALID);
        assertThat(code(() -> UploadPolicy.check(List.of(file(png), file(pdf)))))
                .isEqualTo(ReceiptErrorCode.RECEIPT_FORMAT_MIXED);
    }

    @Test
    void BR_RCP_01_content_type_is_verified_from_the_bytes() {
        assertThat(code(() -> UploadPolicy.check(List.of(file("hello".getBytes(StandardCharsets.UTF_8))))))
                .isEqualTo(ReceiptErrorCode.RECEIPT_FORMAT_UNSUPPORTED);
        assertThat(code(() -> UploadPolicy.check(List.of(file(new byte[0])))))
                .isEqualTo(ReceiptErrorCode.RECEIPT_FORMAT_UNSUPPORTED);
        // An executable renamed receipt.jpg is not a JPEG.
        assertThat(code(() -> UploadPolicy.check(List.of(file(new byte[] {'M', 'Z', (byte) 0x90, 0})))))
                .isEqualTo(ReceiptErrorCode.RECEIPT_FORMAT_UNSUPPORTED);
    }

    @Test
    void BR_RCP_01_heic_is_recognised_and_refused_with_a_conversion_message() {
        byte[] heic = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0};
        assertThat(DocumentFormat.detect(heic)).contains(DocumentFormat.HEIC);
        assertThatThrownBy(() -> UploadPolicy.check(List.of(file(heic))))
                .isInstanceOf(ApplicationException.class).hasMessageContaining("JPEG");
    }

    @Test
    void BR_RCP_01_size_limits_are_10_MB_per_file_and_25_MB_in_total() {
        byte[] tooBig = new byte[(int) UploadPolicy.MAX_FILE_BYTES + 1];
        tooBig[0] = (byte) 0xFF;
        tooBig[1] = (byte) 0xD8;
        tooBig[2] = (byte) 0xFF;
        assertThat(code(() -> UploadPolicy.check(List.of(file(tooBig)))))
                .isEqualTo(ReceiptErrorCode.RECEIPT_FILE_TOO_LARGE);
        byte[] nineMb = new byte[9 * 1024 * 1024];
        nineMb[0] = (byte) 0xFF;
        nineMb[1] = (byte) 0xD8;
        nineMb[2] = (byte) 0xFF;
        assertThat(code(() -> UploadPolicy.check(Collections.nCopies(3, file(nineMb)))))
                .isEqualTo(ReceiptErrorCode.RECEIPT_TOO_LARGE);
        assertThat(UploadPolicy.check(Collections.nCopies(2, file(nineMb))).files()).hasSize(2);
    }
}
