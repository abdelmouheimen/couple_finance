package com.couplefinance.receipt.infrastructure;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import com.couplefinance.receipt.application.PageNormaliser;
import com.couplefinance.receipt.domain.DocumentFormat;
import com.couplefinance.receipt.domain.ReceiptErrorCode;
import com.couplefinance.receipt.domain.UploadPolicy;
import com.couplefinance.shared.error.ApplicationException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * {@link PageNormaliser} with the JDK image codecs and Apache PDFBox. Safety bounds: image dimensions are read from
 * the header before any pixel is decoded (decompression bombs), PDFs must be unencrypted and have at most five
 * pages, every page is rendered at a bounded size, and the output is always a freshly encoded JPEG without metadata
 * (EXIF/GPS/XMP removed). PDF active content (JavaScript, forms, attachments) is never executed: only the page
 * rendering is used, and the PDF itself is not stored.
 */
class JavaPageNormaliser implements PageNormaliser {

    static final int MAX_INPUT_SIDE = 12_000;
    static final long MAX_INPUT_PIXELS = 50_000_000L;
    /** Long side of a stored page: enough for receipt text, bounded storage and analysis cost. */
    static final int MAX_OUTPUT_SIDE = 3_000;
    private static final float JPEG_QUALITY = 0.88f;
    private static final float MAX_PDF_DPI = 200f;

    static {
        ImageIO.setUseCache(false);
    }

    @Override
    public List<NormalisedPage> normalise(DocumentFormat format, byte[] content) {
        return switch (format) {
            case JPEG, PNG -> List.of(encode(decodeImage(content)));
            case PDF -> rasterise(content);
            case HEIC -> throw unreadable();
        };
    }

    private static BufferedImage decodeImage(byte[] content) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (in == null || !readers.hasNext()) {
                throw unreadable();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_INPUT_SIDE || height > MAX_INPUT_SIDE
                        || (long) width * height > MAX_INPUT_PIXELS) {
                    throw unreadable();
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw unreadable();
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApplicationException applicationException) {
                throw applicationException;
            }
            throw unreadable();
        }
    }

    private static List<NormalisedPage> rasterise(byte[] content) {
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(content))) {
            if (document.isEncrypted()) {
                throw unreadable();
            }
            int pages = document.getNumberOfPages();
            if (pages < 1 || pages > UploadPolicy.MAX_PDF_PAGES) {
                throw new ApplicationException(ReceiptErrorCode.RECEIPT_PDF_PAGE_COUNT_INVALID,
                        "A PDF receipt has 1 to " + UploadPolicy.MAX_PDF_PAGES + " pages.");
            }
            PDFRenderer renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            List<NormalisedPage> result = new ArrayList<>();
            for (int i = 0; i < pages; i++) {
                PDRectangle box = document.getPage(i).getCropBox();
                float longSidePoints = Math.max(box.getWidth(), box.getHeight());
                if (!(longSidePoints > 0)) {
                    throw unreadable();
                }
                float dpi = Math.min(MAX_PDF_DPI, MAX_OUTPUT_SIDE * 72f / longSidePoints);
                result.add(encode(renderer.renderImageWithDPI(i, dpi, ImageType.RGB)));
            }
            return result;
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApplicationException applicationException) {
                throw applicationException;
            }
            throw unreadable();
        }
    }

    /** Scales to the output bound, flattens transparency on white and encodes a metadata-free JPEG. */
    static NormalisedPage encode(BufferedImage source) {
        double scale = Math.min(1.0, (double) MAX_OUTPUT_SIDE / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        byte[] jpeg = jpeg(rgb);
        if (jpeg.length > UploadPolicy.MAX_FILE_BYTES) {
            throw unreadable();
        }
        return new NormalisedPage(jpeg, "image/jpeg", width, height, PerceptualHash.of(rgb));
    }

    private static byte[] jpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            // No IIOMetadata passed: the JPEG carries no EXIF, GPS, XMP or comment segment.
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new IllegalStateException("In-memory JPEG encoding failed", e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static ApplicationException unreadable() {
        return new ApplicationException(ReceiptErrorCode.RECEIPT_FILE_UNREADABLE, "This file cannot be read.");
    }
}
