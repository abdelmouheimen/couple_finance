package com.couplefinance.receipt.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * A receipt transcribed as printed (ai.md §4.2), as the receipt domain sees it: untrusted strings, already
 * schema-validated by the AI module, not yet parsed. Built by the application from the AI port's DTO so the domain
 * stays independent of the AI module.
 */
public record ReceiptTranscription(
        String documentType,
        Optional<String> merchantName,
        Optional<String> merchantCountry,
        Optional<String> language,
        Optional<String> purchaseDate,
        String decimalSeparator,
        String dateOrder,
        Optional<String> currencySymbolOrCode,
        String taxMode,
        Optional<String> total,
        Optional<String> handwrittenTotal,
        Optional<String> subtotal,
        List<Tax> taxLines,
        List<Line> lineItems,
        List<String> payments,
        Optional<String> change,
        Optional<BigDecimal> merchantConfidence,
        Optional<BigDecimal> dateConfidence,
        Optional<BigDecimal> totalConfidence,
        Optional<BigDecimal> currencyConfidence) {

    public ReceiptTranscription {
        taxLines = List.copyOf(taxLines);
        lineItems = List.copyOf(lineItems);
        payments = List.copyOf(payments);
    }

    public record Tax(Optional<String> rate, String amount) {
    }

    public record Line(String type, String description, Optional<String> quantity, String amount) {
    }
}
