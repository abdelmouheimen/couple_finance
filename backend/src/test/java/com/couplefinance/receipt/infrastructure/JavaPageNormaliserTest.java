package com.couplefinance.receipt.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import com.couplefinance.receipt.ReceiptFiles;
import com.couplefinance.receipt.application.PageNormaliser.NormalisedPage;
import com.couplefinance.receipt.domain.DocumentFormat;
import com.couplefinance.receipt.domain.ReceiptErrorCode;
import com.couplefinance.shared.error.ApplicationException;
import org.junit.jupiter.api.Test;

/** Adapter contract of the page normaliser: safe decoding, metadata removal, bounded output (security.md §6). */
class JavaPageNormaliserTest {

    private final JavaPageNormaliser normaliser = new JavaPageNormaliser();

    private static ReceiptErrorCode code(Runnable call) {
        try {
            call.run();
        } catch (ApplicationException e) {
            return (ReceiptErrorCode) e.errorCode();
        }
        throw new AssertionError("expected an ApplicationException");
    }

    @Test
    void a_jpeg_is_re_encoded_without_exif_or_gps_metadata() throws IOException {
        byte[] withExif = ReceiptFiles.jpegWithExif(300, 500);
        assertThat(ReceiptFiles.contains(withExif, ReceiptFiles.GPS_MARKER)).isTrue();

        List<NormalisedPage> pages = normaliser.normalise(DocumentFormat.JPEG, withExif);

        assertThat(pages).singleElement().satisfies(page -> {
            assertThat(page.contentType()).isEqualTo("image/jpeg");
            assertThat(page.widthPx()).isEqualTo(300);
            assertThat(page.heightPx()).isEqualTo(500);
            assertThat(ReceiptFiles.contains(page.content(), ReceiptFiles.GPS_MARKER)).isFalse();
            assertThat(ReceiptFiles.contains(page.content(), "Exif")).isFalse();
            assertThat(page.perceptualHash()).isNotNull();
        });
        assertThat(ImageIO.read(new ByteArrayInputStream(pages.get(0).content()))).isNotNull();
    }

    @Test
    void a_transparent_png_becomes_an_opaque_jpeg() {
        NormalisedPage page = normaliser.normalise(DocumentFormat.PNG, ReceiptFiles.png(64, 64)).get(0);
        assertThat(page.contentType()).isEqualTo("image/jpeg");
        assertThat(DocumentFormat.detect(page.content())).contains(DocumentFormat.JPEG);
    }

    @Test
    void large_images_are_downscaled_to_the_output_bound() {
        NormalisedPage page = normaliser.normalise(DocumentFormat.PNG, ReceiptFiles.png(4500, 1500)).get(0);
        assertThat(page.widthPx()).isEqualTo(JavaPageNormaliser.MAX_OUTPUT_SIDE);
        assertThat(page.heightPx()).isEqualTo(1000);
    }

    @Test
    void a_decompression_bomb_header_is_rejected_before_decoding() {
        byte[] bomb = withPngDimensions(ReceiptFiles.png(8, 8), 60_000, 60_000);
        assertThat(code(() -> normaliser.normalise(DocumentFormat.PNG, bomb)))
                .isEqualTo(ReceiptErrorCode.RECEIPT_FILE_UNREADABLE);
    }

    @Test
    void corrupt_files_are_unreadable_never_a_server_error() {
        byte[] truncated = java.util.Arrays.copyOf(ReceiptFiles.jpeg(100, 100), 40);
        assertThat(code(() -> normaliser.normalise(DocumentFormat.JPEG, truncated)))
                .isEqualTo(ReceiptErrorCode.RECEIPT_FILE_UNREADABLE);
        byte[] badPdf = "%PDF-1.7 not really a pdf".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertThat(code(() -> normaliser.normalise(DocumentFormat.PDF, badPdf)))
                .isEqualTo(ReceiptErrorCode.RECEIPT_FILE_UNREADABLE);
        assertThat(code(() -> normaliser.normalise(DocumentFormat.HEIC, new byte[] {1})))
                .isEqualTo(ReceiptErrorCode.RECEIPT_FILE_UNREADABLE);
    }

    @Test
    void BR_RCP_01_a_pdf_is_rasterised_page_by_page_and_never_kept_as_pdf() {
        List<NormalisedPage> pages = normaliser.normalise(DocumentFormat.PDF, ReceiptFiles.pdf(3));
        assertThat(pages).hasSize(3).allSatisfy(page -> {
            assertThat(page.contentType()).isEqualTo("image/jpeg");
            assertThat(DocumentFormat.detect(page.content())).contains(DocumentFormat.JPEG);
            assertThat(Math.max(page.widthPx(), page.heightPx())).isLessThanOrEqualTo(
                    JavaPageNormaliser.MAX_OUTPUT_SIDE);
        });
    }

    @Test
    void BR_RCP_01_a_pdf_of_more_than_five_pages_is_rejected() {
        assertThat(code(() -> normaliser.normalise(DocumentFormat.PDF, ReceiptFiles.pdf(6))))
                .isEqualTo(ReceiptErrorCode.RECEIPT_PDF_PAGE_COUNT_INVALID);
    }

    @Test
    void an_encrypted_pdf_is_rejected() {
        assertThatThrownBy(() -> normaliser.normalise(DocumentFormat.PDF, ReceiptFiles.encryptedPdf()))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ReceiptErrorCode.RECEIPT_FILE_UNREADABLE));
    }

    @Test
    void the_perceptual_hash_matches_the_same_picture_and_differs_for_another() {
        java.awt.image.BufferedImage original = ReceiptFiles.image(400, 600, Color.WHITE, false);
        java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(200, 300,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        g.drawImage(original, 0, 0, 200, 300, null);
        g.dispose();
        long a = PerceptualHash.of(original);
        long sameScaled = PerceptualHash.of(scaled);
        long other = PerceptualHash.of(ReceiptFiles.image(600, 400, Color.WHITE, false));
        assertThat(Long.bitCount(a ^ sameScaled)).isLessThanOrEqualTo(6);
        assertThat(Long.bitCount(a ^ other)).isGreaterThan(6);
    }

    /** Rewrites the IHDR width/height of a PNG (with a valid CRC) without touching the pixel data. */
    private static byte[] withPngDimensions(byte[] png, int width, int height) {
        byte[] copy = png.clone();
        writeInt(copy, 16, width);
        writeInt(copy, 20, height);
        CRC32 crc = new CRC32();
        crc.update(copy, 12, 17);
        writeInt(copy, 29, (int) crc.getValue());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(copy);
        return out.toByteArray();
    }

    private static void writeInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }
}
