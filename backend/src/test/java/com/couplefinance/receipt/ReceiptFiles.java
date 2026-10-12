package com.couplefinance.receipt;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

/** Real, generated receipt files for tests (no binary fixtures in the repository). */
public final class ReceiptFiles {

    /** Marker placed in a fake EXIF/GPS segment: it must never survive re-encoding. */
    public static final String GPS_MARKER = "GPS-48.8566N-2.3522E";

    private ReceiptFiles() {
    }

    public static BufferedImage image(int width, int height, Color background, boolean alpha) {
        BufferedImage image = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(background);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        for (int y = 20; y < height - 20; y += 30) {
            g.fillRect(20, y, width / 2, 8);
            g.fillRect(width - 120, y, 80, 8);
        }
        g.dispose();
        return image;
    }

    public static byte[] png(int width, int height) {
        return encode(image(width, height, Color.WHITE, true), "png");
    }

    public static byte[] jpeg(int width, int height) {
        return encode(image(width, height, Color.WHITE, false), "jpeg");
    }

    /** A JPEG carrying an APP1 "Exif" segment with a fake GPS marker right after the SOI marker. */
    public static byte[] jpegWithExif(int width, int height) {
        byte[] plain = jpeg(width, height);
        byte[] payload = ("Exif\0\0" + GPS_MARKER).getBytes(StandardCharsets.ISO_8859_1);
        int length = payload.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(payload, 0, payload.length);
        out.write(plain, 2, plain.length - 2);
        return out.toByteArray();
    }

    public static byte[] pdf(int pages) {
        return pdf(pages, false);
    }

    public static byte[] encryptedPdf() {
        return pdf(1, true);
    }

    private static byte[] pdf(int pages, boolean encrypted) {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.A6);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.addRect(20, 20 + i * 5, 100, 10);
                    content.fill();
                }
            }
            if (encrypted) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", "user", new AccessPermission());
                policy.setEncryptionKeyLength(128);
                document.protect(policy);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static boolean contains(byte[] haystack, String needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
    }
}
