package com.couplefinance.receipt.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.couplefinance.receipt.domain.ReceiptConsistencyInput.LineItem;
import com.couplefinance.receipt.domain.ReceiptConsistencyInput.TaxLine;
import com.couplefinance.shared.money.CurrencyCode;
import com.couplefinance.shared.money.Money;
import org.junit.jupiter.api.Test;

/** Unit tests for BR-RCP-06/07/11/12/13 deterministic consistency checks. */
class ReceiptConsistencyTest {

    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final CurrencyCode JPY = new CurrencyCode("JPY");

    private static Money eur(String amount) {
        return Money.parse(amount, EUR, 2);
    }

    private static Money jpy(String amount) {
        return Money.parse(amount, JPY, 0);
    }

    private static LineItem item(String amount) {
        return new LineItem(LineItemType.ITEM, eur(amount));
    }

    private static ReceiptConsistencyInput base(String total, List<LineItem> lines) {
        return new ReceiptConsistencyInput(DocumentType.RECEIPT, TaxMode.UNKNOWN, eur(total), Optional.empty(),
                Optional.empty(), List.of(), lines, List.of(), Optional.empty());
    }

    private static ReceiptConsistencyInput withPayments(String total, List<String> payments, String change) {
        return new ReceiptConsistencyInput(DocumentType.RECEIPT, TaxMode.UNKNOWN, eur(total), Optional.empty(),
                Optional.empty(), List.of(), List.of(), payments.stream().map(ReceiptConsistencyTest::eur).toList(),
                Optional.ofNullable(change).map(ReceiptConsistencyTest::eur));
    }

    private static ReceiptConsistencyInput exclusive(String total, String subtotal, String tax) {
        return new ReceiptConsistencyInput(DocumentType.RECEIPT, TaxMode.EXCLUSIVE, eur(total), Optional.empty(),
                Optional.of(eur(subtotal)), List.of(new TaxLine(eur(tax), Optional.empty())), List.of(), List.of(),
                Optional.empty());
    }

    private static ReceiptConsistencyInput inclusive(String total, String tax, String rate) {
        return new ReceiptConsistencyInput(DocumentType.RECEIPT, TaxMode.INCLUSIVE, eur(total), Optional.empty(),
                Optional.empty(), List.of(new TaxLine(eur(tax), Optional.of(new BigDecimal(rate)))), List.of(),
                List.of(), Optional.empty());
    }

    @Test
    void BR_RCP_07_total_ok_when_lines_sum_to_total_with_signed_items() {
        var report = ReceiptConsistencyChecker.check(base("8.00", List.of(item("10.00"),
                new LineItem(LineItemType.DISCOUNT, eur("-2.00")))));
        assertThat(report.totalStatus()).isEqualTo(ReceiptFieldStatus.OK);
        assertThat(report.findings()).isEmpty();
    }

    @Test
    void BR_RCP_07_total_inconsistent_when_lines_do_not_sum() {
        var report = ReceiptConsistencyChecker.check(base("8.00", List.of(item("10.00"))));
        assertThat(report.totalStatus()).isEqualTo(ReceiptFieldStatus.INCONSISTENT);
        assertThat(report.findings()).contains(ConsistencyFinding.LINE_ITEMS_DO_NOT_SUM_TO_TOTAL);
    }

    @Test
    void BR_RCP_07_tolerance_is_at_least_two_cents() {
        assertThat(ReceiptConsistencyChecker.tolerance(eur("1.00"))).isEqualByComparingTo("0.02");
        assertThat(ReceiptConsistencyChecker.tolerance(eur("-1.00"))).isEqualByComparingTo("0.02");
    }

    @Test
    void BR_RCP_07_tolerance_is_one_percent_for_large_totals() {
        assertThat(ReceiptConsistencyChecker.tolerance(eur("500.00"))).isEqualByComparingTo("5.00");
    }

    @Test
    void BR_RCP_07_difference_exactly_at_absolute_tolerance_is_consistent() {
        var report = ReceiptConsistencyChecker.check(base("1.00", List.of(item("1.02"))));
        assertThat(report.totalStatus()).isEqualTo(ReceiptFieldStatus.OK);
    }

    @Test
    void BR_RCP_07_difference_just_above_absolute_tolerance_is_inconsistent() {
        var report = ReceiptConsistencyChecker.check(base("1.00", List.of(item("1.03"))));
        assertThat(report.totalStatus()).isEqualTo(ReceiptFieldStatus.INCONSISTENT);
    }

    @Test
    void BR_RCP_07_difference_exactly_at_relative_tolerance_is_consistent() {
        var report = ReceiptConsistencyChecker.check(base("500.00", List.of(item("495.00"))));
        assertThat(report.totalStatus()).isEqualTo(ReceiptFieldStatus.OK);
    }

    @Test
    void BR_RCP_07_difference_just_above_relative_tolerance_is_inconsistent() {
        var report = ReceiptConsistencyChecker.check(base("500.00", List.of(item("494.99"))));
        assertThat(report.totalStatus()).isEqualTo(ReceiptFieldStatus.INCONSISTENT);
    }

    @Test
    void BR_RCP_07_tolerance_is_symmetric() {
        assertThat(ReceiptConsistencyChecker.check(base("1.00", List.of(item("0.98")))).totalStatus())
                .isEqualTo(ReceiptFieldStatus.OK);
        assertThat(ReceiptConsistencyChecker.check(base("1.00", List.of(item("0.97")))).totalStatus())
                .isEqualTo(ReceiptFieldStatus.INCONSISTENT);
    }

    @Test
    void BR_RCP_07_jpy_uses_whole_units_with_minimum_tolerance_below_one_yen() {
        var input = new ReceiptConsistencyInput(DocumentType.RECEIPT, TaxMode.UNKNOWN, jpy("1000"),
                Optional.empty(), Optional.empty(), List.of(),
                List.of(new LineItem(LineItemType.ITEM, jpy("999"))), List.of(), Optional.empty());
        assertThat(ReceiptConsistencyChecker.check(input).totalStatus()).isEqualTo(ReceiptFieldStatus.OK);
        var far = new ReceiptConsistencyInput(DocumentType.RECEIPT, TaxMode.UNKNOWN, jpy("100"),
                Optional.empty(), Optional.empty(), List.of(),
                List.of(new LineItem(LineItemType.ITEM, jpy("98"))), List.of(), Optional.empty());
        assertThat(ReceiptConsistencyChecker.check(far).totalStatus()).isEqualTo(ReceiptFieldStatus.INCONSISTENT);
    }

    @Test
    void BR_RCP_06_exclusive_subtotal_plus_tax_matches_total() {
        var report = ReceiptConsistencyChecker.check(exclusive("12.00", "10.00", "2.00"));
        assertThat(report.totalStatus()).isEqualTo(ReceiptFieldStatus.OK);
    }

    @Test
    void BR_RCP_06_exclusive_subtotal_plus_tax_mismatch_is_inconsistent() {
        var report = ReceiptConsistencyChecker.check(exclusive("13.00", "10.00", "2.00"));
        assertThat(report.totalStatus()).isEqualTo(ReceiptFieldStatus.INCONSISTENT);
        assertThat(report.findings()).contains(ConsistencyFinding.SUBTOTAL_PLUS_TAX_DIFFERS_FROM_TOTAL);
    }

    @Test
    void BR_RCP_06_unknown_tax_mode_skips_the_tax_check() {
        var input = new ReceiptConsistencyInput(DocumentType.RECEIPT, TaxMode.UNKNOWN, eur("13.00"),
                Optional.empty(), Optional.of(eur("10.00")), List.of(new TaxLine(eur("2.00"), Optional.empty())),
                List.of(), List.of(), Optional.empty());
        var report = ReceiptConsistencyChecker.check(input);
        assertThat(report.totalStatus()).isEqualTo(ReceiptFieldStatus.NOT_CORROBORATED);
        assertThat(report.findings()).isEmpty();
    }

    @Test
    void BR_RCP_06_inclusive_tax_never_marks_total_inconsistent_but_flags_tax_lines() {
        var report = ReceiptConsistencyChecker.check(inclusive("12.00", "5.00", "20"));
        assertThat(report.totalStatus()).isEqualTo(ReceiptFieldStatus.NOT_CORROBORATED);
        assertThat(report.taxLinesStatus()).isEqualTo(ReceiptFieldStatus.INCONSISTENT);
        assertThat(report.findings()).contains(ConsistencyFinding.TAX_DIFFERS_FROM_RATE_DERIVED);
    }

    @Test
    void BR_RCP_06_inclusive_tax_matching_rate_is_ok() {
        var report = ReceiptConsistencyChecker.check(inclusive("12.00", "2.00", "20"));
        assertThat(report.taxLinesStatus()).isEqualTo(ReceiptFieldStatus.OK);
        assertThat(report.findings()).isEmpty();
    }

    @Test
    void BR_RCP_06_inclusive_tax_without_rate_is_informational() {
        var input = new ReceiptConsistencyInput(DocumentType.RECEIPT, TaxMode.INCLUSIVE, eur("12.00"),
                Optional.empty(), Optional.empty(), List.of(new TaxLine(eur("9.00"), Optional.empty())),
                List.of(), List.of(), Optional.empty());
        assertThat(ReceiptConsistencyChecker.check(input).taxLinesStatus()).isEqualTo(ReceiptFieldStatus.OK);
    }

    @Test
    void BR_RCP_07_payments_minus_change_match_total() {
        var report = ReceiptConsistencyChecker.check(withPayments("17.50", List.of("20.00"), "2.50"));
        assertThat(report.totalStatus()).isEqualTo(ReceiptFieldStatus.OK);
    }

    @Test
    void BR_RCP_13_multiple_payment_methods_sum_to_total() {
        var report = ReceiptConsistencyChecker.check(withPayments("30.00", List.of("10.00", "20.00"), null));
        assertThat(report.totalStatus()).isEqualTo(ReceiptFieldStatus.OK);
    }

    @Test
    void BR_RCP_07_payments_mismatch_is_inconsistent() {
        var report = ReceiptConsistencyChecker.check(withPayments("17.50", List.of("20.00"), null));
        assertThat(report.totalStatus()).isEqualTo(ReceiptFieldStatus.INCONSISTENT);
        assertThat(report.findings()).contains(ConsistencyFinding.PAYMENTS_DO_NOT_MATCH_TOTAL);
    }

    @Test
    void BR_RCP_07_total_not_corroborated_without_any_signal() {
        var report = ReceiptConsistencyChecker.check(base("10.00", List.of()));
        assertThat(report.totalStatus()).isEqualTo(ReceiptFieldStatus.NOT_CORROBORATED);
    }

    @Test
    void BR_RCP_07_failed_check_takes_precedence_over_other_passing_checks() {
        var input = new ReceiptConsistencyInput(DocumentType.RECEIPT, TaxMode.UNKNOWN, eur("10.00"),
                Optional.empty(), Optional.empty(), List.of(), List.of(item("10.00")), List.of(eur("12.00")),
                Optional.empty());
        assertThat(ReceiptConsistencyChecker.check(input).totalStatus()).isEqualTo(ReceiptFieldStatus.INCONSISTENT);
    }

    @Test
    void BR_RCP_13_handwritten_total_at_least_printed_is_ambiguous_tip() {
        var input = new ReceiptConsistencyInput(DocumentType.RECEIPT, TaxMode.UNKNOWN, eur("20.00"),
                Optional.of(eur("23.00")), Optional.empty(), List.of(), List.of(item("20.00")), List.of(),
                Optional.empty());
        var report = ReceiptConsistencyChecker.check(input);
        assertThat(report.totalStatus()).isEqualTo(ReceiptFieldStatus.AMBIGUOUS);
        assertThat(report.findings()).contains(ConsistencyFinding.HANDWRITTEN_TOTAL_AT_LEAST_PRINTED);
    }

    @Test
    void BR_RCP_13_handwritten_total_below_printed_is_flagged() {
        var input = new ReceiptConsistencyInput(DocumentType.RECEIPT, TaxMode.UNKNOWN, eur("20.00"),
                Optional.of(eur("18.00")), Optional.empty(), List.of(), List.of(), List.of(), Optional.empty());
        var report = ReceiptConsistencyChecker.check(input);
        assertThat(report.totalStatus()).isEqualTo(ReceiptFieldStatus.AMBIGUOUS);
        assertThat(report.findings()).contains(ConsistencyFinding.HANDWRITTEN_TOTAL_BELOW_PRINTED);
    }

    @Test
    void BR_RCP_13_tip_line_is_reported_and_still_summed() {
        var report = ReceiptConsistencyChecker.check(base("22.00", List.of(item("20.00"),
                new LineItem(LineItemType.TIP, eur("2.00")))));
        assertThat(report.totalStatus()).isEqualTo(ReceiptFieldStatus.OK);
        assertThat(report.findings()).containsExactly(ConsistencyFinding.TIP_LINE_PRESENT);
    }

    @Test
    void BR_RCP_12_quote_and_other_warn_not_a_proof_of_payment() {
        for (DocumentType type : Set.of(DocumentType.QUOTE, DocumentType.OTHER)) {
            var input = new ReceiptConsistencyInput(type, TaxMode.UNKNOWN, eur("10.00"), Optional.empty(),
                    Optional.empty(), List.of(), List.of(), List.of(), Optional.empty());
            assertThat(ReceiptConsistencyChecker.check(input).findings())
                    .contains(ConsistencyFinding.NOT_A_PROOF_OF_PAYMENT);
        }
        assertThat(ReceiptConsistencyChecker.check(base("10.00", List.of())).findings())
                .doesNotContain(ConsistencyFinding.NOT_A_PROOF_OF_PAYMENT);
    }

    @Test
    void BR_RCP_12_refund_receipt_proposes_refund_kind() {
        var input = new ReceiptConsistencyInput(DocumentType.REFUND_RECEIPT, TaxMode.UNKNOWN, eur("10.00"),
                Optional.empty(), Optional.empty(), List.of(), List.of(), List.of(), Optional.empty());
        assertThat(ReceiptConsistencyChecker.check(input).proposedKind()).isEqualTo(ProposedKind.REFUND);
        assertThat(ReceiptConsistencyChecker.check(base("10.00", List.of())).proposedKind())
                .isEqualTo(ProposedKind.EXPENSE);
        assertThat(ReceiptConsistencyChecker.check(base("-10.00", List.of())).proposedKind())
                .isEqualTo(ProposedKind.REFUND);
    }

    @Test
    void BR_RCP_11_validation_status_takes_precedence_over_confidence() {
        Optional<BigDecimal> low = Optional.of(new BigDecimal("0.10"));
        Optional<BigDecimal> threshold = Optional.of(new BigDecimal("0.80"));
        assertThat(ReceiptFieldStatus.withConfidence(ReceiptFieldStatus.INCONSISTENT, low, threshold))
                .isEqualTo(ReceiptFieldStatus.INCONSISTENT);
        assertThat(ReceiptFieldStatus.withConfidence(ReceiptFieldStatus.INVALID, low, threshold))
                .isEqualTo(ReceiptFieldStatus.INVALID);
        assertThat(ReceiptFieldStatus.withConfidence(ReceiptFieldStatus.OK, low, threshold))
                .isEqualTo(ReceiptFieldStatus.LOW_CONFIDENCE);
    }

    @Test
    void BR_RCP_11_confidence_ignored_without_calibrated_threshold() {
        assertThat(ReceiptFieldStatus.withConfidence(ReceiptFieldStatus.OK, Optional.of(new BigDecimal("0.10")),
                Optional.empty())).isEqualTo(ReceiptFieldStatus.OK);
    }

    @Test
    void BR_RCP_11_confidence_at_threshold_is_ok_and_out_of_range_is_low() {
        Optional<BigDecimal> threshold = Optional.of(new BigDecimal("0.80"));
        assertThat(ReceiptFieldStatus.withConfidence(ReceiptFieldStatus.OK, Optional.of(new BigDecimal("0.8")),
                threshold)).isEqualTo(ReceiptFieldStatus.OK);
        assertThat(ReceiptFieldStatus.withConfidence(ReceiptFieldStatus.OK, Optional.of(new BigDecimal("1.5")),
                threshold)).isEqualTo(ReceiptFieldStatus.LOW_CONFIDENCE);
    }

    @Test
    void BR_RCP_04_field_status_from_parse_results() {
        assertThat(ReceiptFieldStatus.fromParse(new ParseResult.Resolved<>(eur("1.00"), Set.of())))
                .isEqualTo(ReceiptFieldStatus.OK);
        assertThat(ReceiptFieldStatus.fromParse(new ParseResult.Resolved<>(eur("1.00"), Set.of(ParseWarning.STALE))))
                .isEqualTo(ReceiptFieldStatus.STALE);
        assertThat(ReceiptFieldStatus.fromParse(new ParseResult.Ambiguous<>(List.of(eur("1.00"), eur("1000.00")))))
                .isEqualTo(ReceiptFieldStatus.AMBIGUOUS);
        assertThat(ReceiptFieldStatus.fromParse(new ParseResult.Invalid<Money>(InvalidReason.UNPARSABLE)))
                .isEqualTo(ReceiptFieldStatus.INVALID);
    }

    @Test
    void BR_RCP_07_validation_does_not_modify_input() {
        var input = base("8.00", List.of(item("10.00")));
        ReceiptConsistencyChecker.check(input);
        assertThat(input.total()).isEqualTo(eur("8.00"));
        assertThat(input.lineItems()).containsExactly(item("10.00"));
    }

    @Test
    void BR_RCP_13_payments_matching_handwritten_tip_total_stay_ambiguous_not_inconsistent() {
        var input = new ReceiptConsistencyInput(DocumentType.RECEIPT, TaxMode.UNKNOWN, eur("20.00"),
                Optional.of(eur("23.00")), Optional.empty(), List.of(), List.of(), List.of(eur("23.00")),
                Optional.empty());
        assertThat(ReceiptConsistencyChecker.check(input).totalStatus()).isEqualTo(ReceiptFieldStatus.AMBIGUOUS);
    }
}
