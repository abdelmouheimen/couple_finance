package com.couplefinance.categorization.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic, versioned merchant-name normalisation (BR-CAT-07), version {@value #VERSION}.
 *
 * <p>Steps: case folding, Unicode decomposition and accent removal, punctuation removal, then removal of
 * trailing store numbers, legal suffixes and city names (never leaving an empty name when a name was
 * present). The pass is repeated until stable, which guarantees idempotence. Pure: no I/O, clock or state.
 * Inputs are never logged.
 *
 * <p><b>Versioning:</b> {@link #VERSION} MUST be incremented whenever the output changes for any input
 * (code or {@link MerchantNormaliserData}); a new immutable golden file
 * {@code merchant-normaliser-golden-vN.tsv} is then added and older golden files are left untouched.
 */
public final class MerchantNameNormaliser {

    /** Version of the function, stored as {@code normaliser_version} (smallint). */
    public static final int VERSION = 1;

    private static final int MAX_PASSES = 16;
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern DIGITS = Pattern.compile("\\p{N}+");

    private static final List<List<String>> SUFFIXES = tokenise(MerchantNormaliserData.LEGAL_SUFFIXES);
    private static final List<List<String>> CITY_TOKENS = tokenise(MerchantNormaliserData.CITIES);

    private MerchantNameNormaliser() {}

    /** Returns the normalised key; the empty string for {@code null}, blank or information-free input. */
    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String current = raw;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            String next = pass(current);
            if (next.equals(current)) {
                return next;
            }
            current = next;
        }
        return current;
    }

    private static String pass(String input) {
        String s = input.toLowerCase(Locale.ROOT);
        s = Normalizer.normalize(s, Normalizer.Form.NFKD);
        s = MARKS.matcher(s).replaceAll("");
        s = s.replace("ß", "ss").replace("æ", "ae").replace("œ", "oe")
                .replace("ø", "o").replace("đ", "d").replace("ł", "l");
        s = NOT_ALNUM.matcher(s).replaceAll(" ").strip();
        if (s.isEmpty()) {
            return "";
        }
        List<String> tokens = new ArrayList<>(List.of(s.split(" ")));
        boolean changed = true;
        while (changed && tokens.size() > 1) {
            changed = stripTrailingStoreNumber(tokens) || stripTrailingSequence(tokens, SUFFIXES)
                    || stripTrailingSequence(tokens, CITY_TOKENS);
        }
        return String.join(" ", tokens);
    }

    /** Removes a trailing all-digit token and a store marker before it; keeps at least one token. */
    private static boolean stripTrailingStoreNumber(List<String> tokens) {
        int last = tokens.size() - 1;
        if (!DIGITS.matcher(tokens.get(last)).matches()) {
            return false;
        }
        tokens.remove(last);
        if (tokens.size() > 1 && MerchantNormaliserData.STORE_MARKERS.contains(tokens.get(tokens.size() - 1))) {
            tokens.remove(tokens.size() - 1);
        }
        return true;
    }

    /** Removes the longest matching trailing token sequence, provided at least one token remains. */
    private static boolean stripTrailingSequence(List<String> tokens, List<List<String>> sequences) {
        int best = 0;
        for (List<String> seq : sequences) {
            int n = seq.size();
            if (n > best && n < tokens.size() && tokens.subList(tokens.size() - n, tokens.size()).equals(seq)) {
                best = n;
            }
        }
        if (best == 0) {
            return false;
        }
        tokens.subList(tokens.size() - best, tokens.size()).clear();
        return true;
    }

    private static List<List<String>> tokenise(List<String> entries) {
        return entries.stream().map(e -> List.of(e.split(" "))).toList();
    }
}
