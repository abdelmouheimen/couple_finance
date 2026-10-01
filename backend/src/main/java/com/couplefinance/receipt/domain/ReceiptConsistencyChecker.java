package com.couplefinance.receipt.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.EnumSet;
import java.util.Set;

import com.couplefinance.receipt.domain.ReceiptConsistencyInput.LineItem;
import com.couplefinance.receipt.domain.ReceiptConsistencyInput.TaxLine;
import com.couplefinance.shared.money.Money;

/**
 * Deterministic consistency checks of an extracted receipt (BR-RCP-06/07/12/13, ai.md §4.4). Pure and total: no
 * I/O, no logging, never modifies a value.
 *
 * <p>Total status precedence: {@code INCONSISTENT} &gt; {@code AMBIGUOUS} (handwritten total) &gt;
 * {@code NOT_CORROBORATED} &gt; {@code OK}. A check corroborates the total only when it was actually performed
 * and passed; INCLUSIVE tax lines are informational and never corroborate.
 */
public final class ReceiptConsistencyChecker {

    private static final BigDecimal MIN_TOLERANCE = new BigDecimal("0.02");
    private static final BigDecimal ONE_PERCENT = new BigDecimal("0.01");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private ReceiptConsistencyChecker() {
    }

    /** BR-RCP-07: max(0.02 currency units, 1 % of |total|), exact {@code BigDecimal}. */
    public static BigDecimal tolerance(Money total) {
        return MIN_TOLERANCE.max(total.amount().abs().multiply(ONE_PERCENT));
    }

    /** True when {@code |a - b| <= tolerance(total)} (exactly at the tolerance is within it). */
    static boolean withinTolerance(Money a, Money b, Money total) {
        return a.amount().subtract(b.amount()).abs().compareTo(tolerance(total)) <= 0;
    }

    public static ReceiptConsistencyReport check(ReceiptConsistencyInput in) {
        Money total = in.total();
        Money zero = Money.zero(total.currency(), total.amount().scale());
        Set<ConsistencyFinding> findings = EnumSet.noneOf(ConsistencyFinding.class);
        boolean failed = false;
        int corroborating = 0;

        if (!in.lineItems().isEmpty()) {
            Money sum = in.lineItems().stream().map(LineItem::amount).reduce(zero, Money::add);
            if (withinTolerance(sum, total, total)) {
                corroborating++;
            } else {
                failed = true;
                findings.add(ConsistencyFinding.LINE_ITEMS_DO_NOT_SUM_TO_TOTAL);
            }
            if (in.lineItems().stream().anyMatch(l -> l.type() == LineItemType.TIP)) {
                findings.add(ConsistencyFinding.TIP_LINE_PRESENT);
            }
        }

        // BR-RCP-06: the subtotal/tax check applies only to EXCLUSIVE.
        if (in.taxMode() == TaxMode.EXCLUSIVE && in.subtotal().isPresent() && !in.taxLines().isEmpty()) {
            Money tax = in.taxLines().stream().map(TaxLine::amount).reduce(zero, Money::add);
            if (withinTolerance(in.subtotal().get().add(tax), total, total)) {
                corroborating++;
            } else {
                failed = true;
                findings.add(ConsistencyFinding.SUBTOTAL_PLUS_TAX_DIFFERS_FROM_TOTAL);
            }
        }

        if (!in.payments().isEmpty()) {
            Money paid = in.payments().stream().reduce(zero, Money::add);
            if (in.change().isPresent()) {
                paid = paid.subtract(in.change().get());
            }
            // BR-RCP-13: with a handwritten total (tip), payments may legitimately match it instead of the printed total.
            Money settled = paid;
            boolean matchesHandwritten = in.handwrittenTotal().isPresent()
                    && withinTolerance(settled, in.handwrittenTotal().get(), in.handwrittenTotal().get());
            if (withinTolerance(paid, total, total) || matchesHandwritten) {
                corroborating++;
            } else {
                failed = true;
                findings.add(ConsistencyFinding.PAYMENTS_DO_NOT_MATCH_TOTAL);
            }
        }

        ReceiptFieldStatus taxLinesStatus = ReceiptFieldStatus.OK;
        if (in.taxMode() == TaxMode.INCLUSIVE && taxLinesDifferFromRateDerived(in, total, zero)) {
            taxLinesStatus = ReceiptFieldStatus.INCONSISTENT;
            findings.add(ConsistencyFinding.TAX_DIFFERS_FROM_RATE_DERIVED);
        }

        boolean ambiguous = false;
        if (in.handwrittenTotal().isPresent()) {
            ambiguous = true;
            findings.add(in.handwrittenTotal().get().compareTo(total) >= 0
                    ? ConsistencyFinding.HANDWRITTEN_TOTAL_AT_LEAST_PRINTED
                    : ConsistencyFinding.HANDWRITTEN_TOTAL_BELOW_PRINTED);
        }

        ReceiptFieldStatus totalStatus;
        if (failed) {
            totalStatus = ReceiptFieldStatus.INCONSISTENT;
        } else if (ambiguous) {
            totalStatus = ReceiptFieldStatus.AMBIGUOUS;
        } else if (corroborating == 0) {
            totalStatus = ReceiptFieldStatus.NOT_CORROBORATED;
        } else {
            totalStatus = ReceiptFieldStatus.OK;
        }

        // BR-RCP-12
        if (in.documentType() == DocumentType.QUOTE || in.documentType() == DocumentType.OTHER) {
            findings.add(ConsistencyFinding.NOT_A_PROOF_OF_PAYMENT);
        }
        ProposedKind kind = in.documentType() == DocumentType.REFUND_RECEIPT
                ? ProposedKind.REFUND
                : ProposedKind.forTotal(total);

        return new ReceiptConsistencyReport(totalStatus, taxLinesStatus, findings, kind);
    }

    /**
     * INCLUSIVE with rates (ai.md §4.4). The taxable base per rate is not extracted, so the check is derivable
     * only when every tax line carries the same positive rate: expected tax = total × r / (100 + r). Otherwise it
     * is skipped (informational only).
     */
    private static boolean taxLinesDifferFromRateDerived(ReceiptConsistencyInput in, Money total, Money zero) {
        if (in.taxLines().isEmpty() || in.taxLines().stream().anyMatch(t -> t.ratePercent().isEmpty())) {
            return false;
        }
        BigDecimal rate = in.taxLines().get(0).ratePercent().orElseThrow();
        boolean single = in.taxLines().stream().allMatch(t -> t.ratePercent().orElseThrow().compareTo(rate) == 0);
        if (!single || rate.signum() <= 0) {
            return false;
        }
        BigDecimal expected = total.amount().multiply(rate).divide(HUNDRED.add(rate), MathContext.DECIMAL128);
        BigDecimal actual = in.taxLines().stream().map(TaxLine::amount).reduce(zero, Money::add).amount();
        return actual.subtract(expected).abs().compareTo(tolerance(total)) > 0;
    }
}
