package com.couplefinance.categorization.domain;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.StringLength;

/** Property-based tests for BR-CAT-07 (determinism, idempotence, totality over arbitrary Unicode). */
class MerchantNameNormaliserPropertyTest {

    @Property(tries = 2000)
    void BR_CAT_07_is_deterministic(@ForAll @StringLength(max = 80) String raw) {
        assertThat(MerchantNameNormaliser.normalise(raw)).isEqualTo(MerchantNameNormaliser.normalise(raw));
    }

    @Property(tries = 2000)
    void BR_CAT_07_is_idempotent(@ForAll @StringLength(max = 80) String raw) {
        String once = MerchantNameNormaliser.normalise(raw);
        assertThat(MerchantNameNormaliser.normalise(once)).isEqualTo(once);
    }

    @Property(tries = 2000)
    void BR_CAT_07_never_fails_and_never_returns_null_or_untidy_output(@ForAll @StringLength(max = 200) String raw) {
        String key = MerchantNameNormaliser.normalise(raw);
        assertThat(key).isNotNull().isEqualTo(key.strip()).doesNotContain("  ");
    }
}
