package com.couplefinance.receipt.infrastructure;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * 64-bit DCT perceptual hash (pHash) of an image, stored for duplicate detection of the same paper photographed
 * twice (BR-RCP-14 b, database-schema.md section 8.9). Image geometry only; unrelated to money.
 */
final class PerceptualHash {

    private static final int SIZE = 32;
    private static final int LOW = 8;

    private PerceptualHash() {
    }

    static long of(BufferedImage image) {
        BufferedImage small = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = small.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, SIZE, SIZE, null);
        } finally {
            g.dispose();
        }
        double[][] pixels = new double[SIZE][SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                pixels[y][x] = small.getRaster().getSample(x, y, 0);
            }
        }
        double[] coefficients = new double[LOW * LOW];
        for (int v = 0; v < LOW; v++) {
            for (int u = 0; u < LOW; u++) {
                double sum = 0;
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        sum += pixels[y][x] * Math.cos((2 * x + 1) * u * Math.PI / (2 * SIZE))
                                * Math.cos((2 * y + 1) * v * Math.PI / (2 * SIZE));
                    }
                }
                coefficients[v * LOW + u] = sum;
            }
        }
        // The DC term (overall brightness) is excluded from the median.
        double[] sorted = Arrays.copyOfRange(coefficients, 1, coefficients.length);
        Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];
        long hash = 0;
        for (int i = 0; i < coefficients.length; i++) {
            if (coefficients[i] > median) {
                hash |= 1L << i;
            }
        }
        return hash;
    }
}
