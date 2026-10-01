package com.couplefinance.categorization.api;

import java.util.Objects;

/**
 * Result of merchant-name normalisation (BR-CAT-07): the normalised {@code key} and the
 * {@code normaliserVersion} that produced it. The key is the empty string when the input carries no merchant
 * information; it is never {@code null}.
 */
public record NormalisedMerchant(String key, int normaliserVersion) {

    public NormalisedMerchant {
        Objects.requireNonNull(key, "key");
    }

    public boolean isEmpty() {
        return key.isEmpty();
    }
}
