package com.couplefinance.receipt.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.couplefinance.shared.money.Money;

/**
 * Already-parsed values of one extracted receipt (RECEIPT-004), all in the receipt currency. Absent values are
 * empty Optionals / empty lists.
 */
public record ReceiptConsistencyInput(
        DocumentType documentType,
        TaxMode taxMode,
        Money total,
        Optional<Money> handwrittenTotal,
        Optional<Money> subtotal,
        List<TaxLine> taxLines,
        List<LineItem> lineItems,
        List<Money> payments,
        Optional<Money> change) {

    /** A printed tax line; {@code ratePercent} is e.g. 20 for 20 %. */
    public record TaxLine(Money amount, Optional<BigDecimal> ratePercent) {
        public TaxLine {
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(ratePercent, "ratePercent");
        }
    }

    /** A signed, typed line item (BR-RCP-04). */
    public record LineItem(LineItemType type, Money amount) {
        public LineItem {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(amount, "amount");
        }
    }

    public ReceiptConsistencyInput {
        Objects.requireNonNull(documentType, "documentType");
        Objects.requireNonNull(taxMode, "taxMode");
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(handwrittenTotal, "handwrittenTotal");
        Objects.requireNonNull(subtotal, "subtotal");
        Objects.requireNonNull(change, "change");
        taxLines = List.copyOf(taxLines);
        lineItems = List.copyOf(lineItems);
        payments = List.copyOf(payments);
        var currency = total.currency();
        boolean sameCurrency = handwrittenTotal.stream().allMatch(m -> m.currency().equals(currency))
                && subtotal.stream().allMatch(m -> m.currency().equals(currency))
                && change.stream().allMatch(m -> m.currency().equals(currency))
                && taxLines.stream().allMatch(t -> t.amount().currency().equals(currency))
                && lineItems.stream().allMatch(l -> l.amount().currency().equals(currency))
                && payments.stream().allMatch(m -> m.currency().equals(currency));
        if (!sameCurrency) {
            throw new IllegalArgumentException("All amounts of one receipt must share the receipt currency.");
        }
    }
}
