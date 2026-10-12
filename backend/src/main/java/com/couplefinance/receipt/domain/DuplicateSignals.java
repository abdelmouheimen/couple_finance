package com.couplefinance.receipt.domain;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Pure duplicate signals of BR-RCP-14. They only ever produce warnings: a duplicate is never blocked or discarded,
 * the member decides (confirm anyway, attach to the existing expense, or delete the receipt).
 */
public final class DuplicateSignals {

    /**
     * BR-RCP-14 (b): two 64-bit perceptual hashes at most this many bits apart are "the same paper photographed
     * twice". [proposal] 6 of 64 bits; to be calibrated on the golden dataset.
     */
    public static final int PERCEPTUAL_MAX_DISTANCE = 6;

    /** BR-RCP-14 (c): merchant keys match when equal or when their trigram similarity is at least this. */
    public static final double MERCHANT_SIMILARITY = 0.6;

    private DuplicateSignals() {
    }

    public static int distance(long hashA, long hashB) {
        return Long.bitCount(hashA ^ hashB);
    }

    public static boolean similarImage(long hashA, long hashB) {
        return distance(hashA, hashB) <= PERCEPTUAL_MAX_DISTANCE;
    }

    /** BR-RCP-14 (c): equal keys, or trigram similarity ≥ 0.6. Blank keys never match. */
    public static boolean sameMerchant(String keyA, String keyB) {
        if (keyA == null || keyB == null || keyA.isBlank() || keyB.isBlank()) {
            return false;
        }
        return keyA.equals(keyB) || trigramSimilarity(keyA, keyB) >= MERCHANT_SIMILARITY;
    }

    /**
     * Trigram similarity as defined by PostgreSQL {@code pg_trgm}: each word is lower-cased and padded with two
     * leading and one trailing blank; similarity = |shared trigrams| / |all distinct trigrams|.
     */
    public static double trigramSimilarity(String a, String b) {
        Set<String> first = trigrams(a);
        Set<String> second = trigrams(b);
        if (first.isEmpty() || second.isEmpty()) {
            return 0;
        }
        Set<String> shared = new HashSet<>(first);
        shared.retainAll(second);
        Set<String> all = new HashSet<>(first);
        all.addAll(second);
        return (double) shared.size() / all.size();
    }

    private static Set<String> trigrams(String text) {
        Set<String> trigrams = new HashSet<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
            if (word.isEmpty()) {
                continue;
            }
            String padded = "  " + word + " ";
            for (int i = 0; i + 3 <= padded.length(); i++) {
                trigrams.add(padded.substring(i, i + 3));
            }
        }
        return trigrams;
    }
}
