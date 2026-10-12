package com.couplefinance.ai.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The ai.md §4.2 extraction schema, provider-neutral, schema-validated, NOT business-validated: every amount and date
 * is the string as printed. Enumerations are kept as validated strings so the receipt module owns their meaning.
 */
public record DocumentExtraction(
        String documentType,
        Optional<String> merchantName,
        Optional<String> merchantCountry,
        Optional<String> language,
        Optional<String> purchaseDate,
        ObservedFormats observedFormats,
        Optional<String> currencySymbolOrCode,
        String taxMode,
        Optional<String> total,
        Optional<String> handwrittenTotal,
        Optional<String> subtotal,
        List<TaxLine> taxLines,
        List<LineItem> lineItems,
        List<Payment> payments,
        Optional<String> change,
        Confidence confidence) {

    public DocumentExtraction {
        Objects.requireNonNull(documentType, "documentType");
        Objects.requireNonNull(observedFormats, "observedFormats");
        Objects.requireNonNull(taxMode, "taxMode");
        Objects.requireNonNull(confidence, "confidence");
        taxLines = List.copyOf(taxLines);
        lineItems = List.copyOf(lineItems);
        payments = List.copyOf(payments);
    }

    /** @param decimalSeparator {@code .}, {@code ,} or {@code unknown}; dateOrder DMY, MDY, YMD or unknown */
    public record ObservedFormats(String decimalSeparator, String groupingSeparator, String dateOrder) {
    }

    public record TaxLine(String label, Optional<String> rate, String amount) {
    }

    /** @param type ITEM, DISCOUNT, DEPOSIT, DEPOSIT_RETURN, TIP, FEE or OTHER; amount signed as printed */
    public record LineItem(String type, String description, Optional<String> quantity, String amount) {
    }

    /** @param method CARD, CASH or OTHER */
    public record Payment(String method, String amount) {
    }

    /** Model-reported confidences in [0, 1]; uncalibrated, never used to mark a field OK (BR-RCP-11). */
    public record Confidence(Optional<BigDecimal> merchantName, Optional<BigDecimal> purchaseDate,
            Optional<BigDecimal> total, Optional<BigDecimal> currency) {
    }
}
