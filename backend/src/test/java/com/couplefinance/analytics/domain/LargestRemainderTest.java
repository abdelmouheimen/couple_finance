package com.couplefinance.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/** BR-ANA-05, BR-ANA-06, BR-MON-09: largest-remainder percentages. */
class LargestRemainderTest {

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void BR_ANA_05_percentages_sum_to_100() {
        // 1/3 each: 33.3 + 33.3 + 33.3 = 99.9, the missing tenth goes to the first of the equal remainders.
        List<BigDecimal> shares = LargestRemainder.percentages(List.of(100L, 100L, 100L));

        assertThat(shares).containsExactly(new BigDecimal("33.4"), new BigDecimal("33.3"), new BigDecimal("33.3"));
        assertThat(sum(shares)).isEqualByComparingTo("100.0");
    }

    @Test
    void BR_ANA_05_the_largest_remainder_gets_the_extra_tenth() {
        // 1/6, 1/6, 2/3: floors 166, 166, 666 (sum 998), all remainders equal: ties go to the larger amount, then
        // to the lower index.
        List<BigDecimal> shares = LargestRemainder.percentages(List.of(1L, 1L, 4L));

        assertThat(shares).containsExactly(new BigDecimal("16.7"), new BigDecimal("16.6"), new BigDecimal("66.7"));
        assertThat(sum(shares)).isEqualByComparingTo("100.0");
    }

    @Test
    void BR_ANA_05_exact_shares_are_untouched_and_scale_is_one() {
        List<BigDecimal> shares = LargestRemainder.percentages(List.of(600_00L, 200_00L));

        assertThat(shares).containsExactly(new BigDecimal("75.0"), new BigDecimal("25.0"));
        assertThat(shares).allMatch(share -> share.scale() == 1);
    }

    @Test
    void BR_ANA_05_a_single_category_is_100() {
        assertThat(LargestRemainder.percentages(List.of(1L))).containsExactly(new BigDecimal("100.0"));
    }

    @Test
    void BR_ANA_05_nothing_to_share_gives_zero_percentages() {
        assertThat(LargestRemainder.percentages(List.of())).isEmpty();
        assertThat(LargestRemainder.percentages(List.of(0L, 0L))).containsExactly(new BigDecimal("0.0"),
                new BigDecimal("0.0"));
    }

    @Test
    void BR_ANA_06_negative_category_excluded_from_percentages() {
        // a negative net amount has no share: the caller excludes it, the method refuses it
        assertThatThrownBy(() -> LargestRemainder.percentages(List.of(100L, -30L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Property
    void BR_ANA_05_percentages_always_sum_to_100_and_never_exceed_the_exact_share_by_a_tenth(
            @ForAll("amounts") List<Long> amounts) {
        List<BigDecimal> shares = LargestRemainder.percentages(amounts);

        long total = amounts.stream().mapToLong(Long::longValue).sum();
        assertThat(shares).hasSameSizeAs(amounts);
        if (total == 0) {
            assertThat(sum(shares)).isEqualByComparingTo("0.0");
            return;
        }
        assertThat(sum(shares)).isEqualByComparingTo("100.0");
        for (int i = 0; i < amounts.size(); i++) {
            BigDecimal exact = BigDecimal.valueOf(amounts.get(i)).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(total), 10, java.math.RoundingMode.HALF_EVEN);
            assertThat(shares.get(i).subtract(exact).abs()).isLessThan(new BigDecimal("0.1"));
        }
    }

    @Property
    void BR_ANA_05_the_result_is_deterministic(@ForAll("amounts") List<Long> amounts) {
        assertThat(LargestRemainder.percentages(amounts)).isEqualTo(LargestRemainder.percentages(amounts));
    }

    @Provide
    Arbitrary<List<Long>> amounts() {
        return Arbitraries.longs().between(0, 1_000_000_000_000L).list().ofMinSize(1).ofMaxSize(12);
    }
}
