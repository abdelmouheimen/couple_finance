package com.couplefinance.receipt.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.couplefinance.shared.money.CurrencyCode;
import com.couplefinance.shared.money.Money;
import org.jspecify.annotations.Nullable;

/**
 * Deterministic construction of the review draft from a transcription (ai.md §4.3–4.4, ADR-004): amounts, dates and
 * currency go through the existing parsers with ambiguity detection; consistency checks run only on fully resolved
 * values; nothing is ever corrected, rounded or guessed. Pure: no I/O, no clock (today is passed in).
 */
public final class ReceiptDraftBuilder {

    private ReceiptDraftBuilder() {
    }

    /**
     * @param maximum BR-MON-07 maximum amount in the household currency (its currency and scale drive parsing)
     * @param today   today in the household time zone (BR-HH-05)
     */
    public static ReceiptDraft build(ReceiptTranscription t, CurrencyCode householdCurrency, Money maximum,
            LocalDate today, Function<String, Optional<String>> merchantKey) {
        List<Locale> locales = locales(t);
        Map<String, ReceiptDraft.Field> fields = new LinkedHashMap<>();
        Set<String> findings = new LinkedHashSet<>();

        DocumentType documentType = DocumentType.valueOf(t.documentType());
        TaxMode taxMode = TaxMode.valueOf(t.taxMode());
        if (documentType == DocumentType.QUOTE || documentType == DocumentType.OTHER) {
            findings.add(ConsistencyFinding.NOT_A_PROOF_OF_PAYMENT.name());
        }

        Optional<String> merchant = t.merchantName().map(String::strip).filter(s -> !s.isEmpty());
        Optional<String> key = merchant.flatMap(merchantKey);
        fields.put("merchant", new ReceiptDraft.Field(merchant.orElse(null), List.of(),
                merchant.isPresent() ? ReceiptFieldStatus.OK.name() : ReceiptDraft.MISSING,
                t.merchantConfidence().orElse(null)));

        // Currency (BR-RCP-09): a currency other than the household's is flagged.
        Optional<String> currency = Optional.empty();
        if (t.currencySymbolOrCode().isEmpty()) {
            fields.put("currency", new ReceiptDraft.Field(null, List.of(), ReceiptDraft.MISSING,
                    t.currencyConfidence().orElse(null)));
        } else {
            ParseResult<CurrencyCode> parsed = CurrencyParser.parse(t.currencySymbolOrCode().get(), locales,
                    householdCurrency);
            if (parsed instanceof ParseResult.Resolved<CurrencyCode> resolved) {
                currency = Optional.of(resolved.value().value());
                if (resolved.warnings().contains(ParseWarning.CURRENCY_DIFFERS_FROM_HOUSEHOLD)) {
                    findings.add(ParseWarning.CURRENCY_DIFFERS_FROM_HOUSEHOLD.name());
                }
            }
            fields.put("currency", field(t.currencySymbolOrCode().get(), parsed, CurrencyCode::value,
                    t.currencyConfidence().orElse(null)));
        }

        // Every printed amount helps disambiguate the others (BR-RCP-05).
        List<String> others = new ArrayList<>();
        t.lineItems().forEach(l -> others.add(l.amount()));
        others.addAll(t.payments());
        t.subtotal().ifPresent(others::add);
        t.taxLines().forEach(x -> others.add(x.amount()));
        AmountHints amountHints = new AmountHints(decimalMark(t.decimalSeparator()), locales, others);

        List<ReceiptDraft.Line> lines = new ArrayList<>();
        List<ReceiptConsistencyInput.LineItem> resolvedLines = new ArrayList<>();
        boolean allLinesResolved = true;
        for (int i = 0; i < t.lineItems().size(); i++) {
            ReceiptTranscription.Line line = t.lineItems().get(i);
            LineItemType type = LineItemType.valueOf(line.type());
            ParseResult<Money> amount = LineItemAmountParser.parse(type, line.amount(), maximum, amountHints);
            String status = switch (amount) {
                case ParseResult.Resolved<Money> ignored -> "OK";
                case ParseResult.Ambiguous<Money> ignored -> "AMBIGUOUS";
                case ParseResult.Invalid<Money> ignored -> "INVALID";
            };
            Long minor = null;
            if (amount instanceof ParseResult.Resolved<Money> resolved) {
                minor = resolved.value().minorUnits();
                resolvedLines.add(new ReceiptConsistencyInput.LineItem(type, resolved.value()));
            } else {
                allLinesResolved = false;
            }
            lines.add(new ReceiptDraft.Line(i + 1, type, truncate(line.description(), 200),
                    line.quantity().orElse(null), line.amount(), minor, status));
        }

        Optional<Long> totalMinor = Optional.empty();
        ProposedKind kind = documentType == DocumentType.REFUND_RECEIPT ? ProposedKind.REFUND : ProposedKind.EXPENSE;
        if (t.total().isEmpty()) {
            fields.put("total", new ReceiptDraft.Field(null, List.of(), ReceiptDraft.MISSING,
                    t.totalConfidence().orElse(null)));
        } else {
            ParseResult<Money> total = AmountParser.parse(t.total().get(), maximum, amountHints);
            ReceiptDraft.Field field = field(t.total().get(), total, Money::toDecimalString,
                    t.totalConfidence().orElse(null));
            if (total instanceof ParseResult.Resolved<Money> resolved && !resolved.value().isZero()) {
                totalMinor = Optional.of(resolved.value().minorUnits());
                ReceiptConsistencyReport report = ReceiptConsistencyChecker.check(new ReceiptConsistencyInput(
                        documentType, taxMode, resolved.value(),
                        optionalAmount(t.handwrittenTotal(), maximum, amountHints),
                        optionalAmount(t.subtotal(), maximum, amountHints),
                        allResolvedTaxes(t, maximum, amountHints),
                        allLinesResolved ? resolvedLines : List.of(),
                        allResolvedPayments(t, maximum, amountHints),
                        optionalAmount(t.change(), maximum, amountHints)));
                report.findings().forEach(f -> findings.add(f.name()));
                if (documentType != DocumentType.REFUND_RECEIPT) {
                    kind = report.proposedKind();
                }
                field = new ReceiptDraft.Field(field.raw(), field.candidates(), report.totalStatus().name(),
                        field.confidence());
            }
            fields.put("total", field);
        }

        Optional<LocalDate> date = Optional.empty();
        if (t.purchaseDate().isEmpty()) {
            fields.put("date", new ReceiptDraft.Field(null, List.of(), ReceiptDraft.MISSING,
                    t.dateConfidence().orElse(null)));
        } else {
            ParseResult<LocalDate> parsed = ReceiptDateParser.parse(t.purchaseDate().get(),
                    new DateHints(dateOrder(t.dateOrder()), locales), today);
            if (parsed instanceof ParseResult.Resolved<LocalDate> resolved) {
                date = Optional.of(resolved.value());
            }
            fields.put("date", field(t.purchaseDate().get(), parsed, LocalDate::toString,
                    t.dateConfidence().orElse(null)));
        }

        return new ReceiptDraft(documentType, taxMode, kind, key, totalMinor, date, currency, fields, lines,
                findings);
    }

    private static <T> ReceiptDraft.Field field(String raw, ParseResult<T> parsed, Function<T, String> show,
            @Nullable BigDecimal confidence) {
        List<String> candidates = parsed instanceof ParseResult.Ambiguous<T> ambiguous
                ? ambiguous.candidates().stream().map(show).toList()
                : List.of();
        return new ReceiptDraft.Field(raw, candidates, ReceiptFieldStatus.fromParse(parsed).name(), confidence);
    }

    private static Optional<Money> optionalAmount(Optional<String> raw, Money maximum, AmountHints hints) {
        return raw.flatMap(r -> AmountParser.parse(r, maximum, hints) instanceof ParseResult.Resolved<Money> m
                ? Optional.of(m.value()) : Optional.empty());
    }

    /** All payment amounts, or none when one cannot be resolved (a partial sum would be a false flag). */
    private static List<Money> allResolvedPayments(ReceiptTranscription t, Money maximum, AmountHints hints) {
        List<Money> payments = new ArrayList<>();
        for (String raw : t.payments()) {
            if (!(AmountParser.parse(raw, maximum, hints) instanceof ParseResult.Resolved<Money> m)) {
                return List.of();
            }
            payments.add(m.value());
        }
        return payments;
    }

    private static List<ReceiptConsistencyInput.TaxLine> allResolvedTaxes(ReceiptTranscription t, Money maximum,
            AmountHints hints) {
        List<ReceiptConsistencyInput.TaxLine> taxes = new ArrayList<>();
        for (ReceiptTranscription.Tax tax : t.taxLines()) {
            if (!(AmountParser.parse(tax.amount(), maximum, hints) instanceof ParseResult.Resolved<Money> m)) {
                return List.of();
            }
            taxes.add(new ReceiptConsistencyInput.TaxLine(m.value(), Optional.empty()));
        }
        return taxes;
    }

    private static List<Locale> locales(ReceiptTranscription t) {
        List<Locale> locales = new ArrayList<>();
        String language = t.language().map(String::strip).orElse("");
        String country = t.merchantCountry().map(String::strip).orElse("");
        if (!language.isEmpty()) {
            locales.add(Locale.forLanguageTag(country.matches("[A-Za-z]{2}") && !language.contains("-")
                    ? language + "-" + country.toUpperCase(Locale.ROOT) : language));
        }
        return locales;
    }

    private static Optional<DecimalMark> decimalMark(String separator) {
        return switch (separator) {
            case "." -> Optional.of(DecimalMark.DOT);
            case "," -> Optional.of(DecimalMark.COMMA);
            default -> Optional.empty();
        };
    }

    private static Optional<DateOrder> dateOrder(String order) {
        return EnumSet.allOf(DateOrder.class).stream().filter(o -> o.name().equals(order)).findFirst();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
