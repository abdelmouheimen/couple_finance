package com.couplefinance.categorization.api;

import com.couplefinance.categorization.domain.MerchantNameNormaliser;

/** Public entry point of the merchant-name normalisation (BR-CAT-07). Pure: no I/O, no clock, no state. */
public final class MerchantNames {

    /** Version of the normalisation function; recorded by merchant rules and duplicate detection. */
    public static final int NORMALISER_VERSION = MerchantNameNormaliser.VERSION;

    private MerchantNames() {}

    /** Normalises a raw merchant string; {@code null}, blank or information-free input yields an empty key. */
    public static NormalisedMerchant normalise(String rawMerchant) {
        return new NormalisedMerchant(MerchantNameNormaliser.normalise(rawMerchant), NORMALISER_VERSION);
    }
}
