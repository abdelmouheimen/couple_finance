package com.couplefinance.receipt.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import com.couplefinance.receipt.domain.ReceiptConsistencyInput.LineItem;
import com.couplefinance.shared.money.CurrencyCode;
import com.couplefinance.shared.money.Money;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

/** Property-based tests for BR-RCP-07 consistency checks. */
class ReceiptConsistencyPropertyTest {

    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final CurrencyCode JPY = new CurrencyCode("JPY");

    private static ReceiptConsistencyInput lines(CurrencyCode cur, int decimals, Money total, List<Long> minors) {
        List<LineItem> items = minors.stream()
                .map(m -> new LineItem(LineItemType.OTHER, Money.ofMinor(m, cur, decimals))).toList();
        return new ReceiptConsistencyInput(DocumentType.RECEIPT, TaxMode.UNKNOWN, total, Optional.empty(),
                Optional.empty(), List.of(), items, List.of(), Optional.empty());
    }

    @Property(tries = 2000)
    void BR_RCP_07_consistent_receipt_built_from_known_lines_is_never_flagged(
            @ForAll @Size(min = 1, max = 20) List<@LongRange(min = -100_000, max = 100_000) Long> minors) {
        long sum = minors.stream().mapToLong(Long::longValue).sum();
        var input = lines(EUR, 2, Money.ofMinor(sum, EUR, 2), minors);
        assertThat(ReceiptConsistencyChecker.check(input).totalStatus()).isEqualTo(ReceiptFieldStatus.OK);
    }

    @Property(tries = 2000)
    void BR_RCP_07_consistent_jpy_receipt_is_never_flagged(
            @ForAll @Size(min = 1, max = 20) List<@LongRange(min = 0, max = 100_000) Long> minors) {
        long sum = minors.stream().mapToLong(Long::longValue).sum();
        var input = lines(JPY, 0, Money.ofMinor(sum, JPY, 0), minors);
        assertThat(ReceiptConsistencyChecker.check(input).totalStatus()).isEqualTo(ReceiptFieldStatus.OK);
    }

    @Property(tries = 2000)
    void BR_RCP_07_tolerance_is_symmetric(@ForAll @LongRange(min = 0, max = 10_000_000) long totalMinor,
            @ForAll @IntRange(min = 0, max = 200_000) int delta) {
        Money total = Money.ofMinor(totalMinor, EUR, 2);
        var up = lines(EUR, 2, total, List.of(totalMinor + delta));
        var down = lines(EUR, 2, total, List.of(totalMinor - delta));
        assertThat(ReceiptConsistencyChecker.check(up).totalStatus())
                .isEqualTo(ReceiptConsistencyChecker.check(down).totalStatus());
    }

    @Property(tries = 2000)
    void BR_RCP_07_check_is_total_and_deterministic(@ForAll @LongRange(min = -10_000_000, max = 10_000_000) long total,
            @ForAll @Size(max = 10) List<@LongRange(min = -1_000_000, max = 1_000_000) Long> minors) {
        var input = lines(EUR, 2, Money.ofMinor(total, EUR, 2), minors);
        assertThat(ReceiptConsistencyChecker.check(input)).isEqualTo(ReceiptConsistencyChecker.check(input));
    }
}
