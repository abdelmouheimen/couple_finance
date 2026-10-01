package com.couplefinance.categorization.domain;

import java.util.List;
import java.util.Set;

/**
 * Versioned reference data of merchant normaliser version 1 (BR-CAT-07). Entries are already normalised
 * (lower-case, single-space separated tokens). Any change to these lists changes the output of the function
 * and therefore requires a new {@link MerchantNameNormaliser#VERSION} (guarded by the golden-file test).
 */
final class MerchantNormaliserData {

    private MerchantNormaliserData() {}

    /** Legal forms, removed when they trail the name. Dotted forms ("s.a.s.") arrive as spaced letters. */
    static final List<String> LEGAL_SUFFIXES = List.of(
            "sa", "sas", "sasu", "sarl", "eurl", "sci", "snc", "scop",
            "s a", "s a s", "s a s u", "s a r l", "e u r l",
            "gmbh", "ag", "kg", "ohg", "ug", "g m b h",
            "ltd", "limited", "llc", "l l c", "inc", "corp", "co", "plc", "p l c",
            "bv", "nv", "b v", "n v",
            "spa", "srl", "s p a", "s r l", "sl", "s l");

    /** Markers that may precede a trailing store number ("n° 12", "store 12"). */
    static final Set<String> STORE_MARKERS = Set.of("n", "no", "nr", "num", "numero", "store", "shop", "magasin");

    /** Large cities (FR + main EU) removed when they trail the name. */
    static final List<String> CITIES = List.of(
            "paris", "marseille", "lyon", "toulouse", "nice", "nantes", "montpellier", "strasbourg",
            "bordeaux", "lille", "rennes", "reims", "toulon", "grenoble", "dijon", "angers", "nimes",
            "villeurbanne", "clermont ferrand", "le mans", "aix en provence", "brest", "tours", "amiens",
            "limoges", "annecy", "perpignan", "metz", "besancon", "orleans", "rouen", "mulhouse", "caen",
            "nancy", "saint denis", "argenteuil", "montreuil", "roubaix", "tourcoing", "avignon", "poitiers",
            "versailles", "boulogne billancourt", "la defense", "creteil", "nanterre",
            "brussels", "bruxelles", "antwerp", "gent", "geneve", "geneva", "zurich", "lausanne",
            "london", "dublin", "amsterdam", "rotterdam", "berlin", "munich", "hamburg", "frankfurt",
            "cologne", "madrid", "barcelona", "valencia", "lisbon", "lisboa", "porto", "rome", "roma",
            "milan", "milano", "turin", "vienna", "wien", "luxembourg");
}
