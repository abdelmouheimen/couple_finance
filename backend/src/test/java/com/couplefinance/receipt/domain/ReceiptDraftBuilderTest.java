package com.couplefinance.receipt.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.couplefinance.shared.money.CurrencyCode;
import com.couplefinance.shared.money.Money;
import org.junit.jupiter.api.Test;

/**
 * Financial accuracy of the review draft (DOC-003, ADR-004): the transcription is untrusted strings; every amount is
 * parsed deterministically, exactly, and never guessed (BR-RCP-04/05/07/08/09, BR-MON-08).
 */
class ReceiptDraftBuilderTest {

    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final Money MAX_EUR = Money.ofMinor(100_000_000L, EUR, 2);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

    /** Mutable description of a transcription, French supermarket receipt by default. */
    private static final class Printed {
        String documentType = "RECEIPT";
        Optional<String> merchant = Optional.of("Carrefour");
        Optional<String> country = Optional.of("FR");
        Optional<String> language = Optional.of("fr");
        Optional<String> date = Optional.of("04/10/2026");
        String decimalSeparator = ",";
        String dateOrder = "DMY";
        Optional<String> currency = Optional.of("€");
        Optional<String> total = Optional.of("13,78");
        List<ReceiptTranscription.Line> lines = List.of(line("ITEM", "LAIT BIO", "1,29"), line("ITEM", "PAIN", "1,10"),
                line("ITEM", "POULET", "8,90"), line("ITEM", "TOMATES", "2,49"));
        List<String> payments = List.of("13,78");

        ReceiptTranscription build() {
            return new ReceiptTranscription(documentType, merchant, country, language, date, decimalSeparator,
                    dateOrder, currency, "INCLUSIVE", total, Optional.empty(), Optional.empty(), List.of(), lines,
                    payments, Optional.empty(), Optional.of(new BigDecimal("0.98")), Optional.empty(),
                    Optional.of(new BigDecimal("0.99")), Optional.empty());
        }
    }

    private static ReceiptTranscription.Line line(String type, String description, String amount) {
        return new ReceiptTranscription.Line(type, description, Optional.empty(), amount);
    }

    private static ReceiptDraft draft(Printed printed) {
        return ReceiptDraftBuilder.build(printed.build(), EUR, MAX_EUR, TODAY,
                raw -> Optional.of(raw.toLowerCase(java.util.Locale.ROOT)));
    }

    @Test
    void BR_RCP_04_a_consistent_receipt_yields_exact_minor_units_and_resolved_fields() {
        ReceiptDraft draft = draft(new Printed());

        assertThat(draft.totalMinor()).contains(1378L);
        assertThat(draft.purchaseDate()).contains(LocalDate.of(2026, 10, 4));
        assertThat(draft.detectedCurrency()).contains("EUR");
        assertThat(draft.merchantKey()).contains("carrefour");
        assertThat(draft.proposedKind()).isEqualTo(ProposedKind.EXPENSE);
        assertThat(draft.fields().get("total").status()).isEqualTo("OK");
        assertThat(draft.fields().get("total").raw()).isEqualTo("13,78");
        assertThat(draft.fields().get("total").confidence()).isEqualByComparingTo("0.99");
        assertThat(draft.fields().get("date").status()).isEqualTo("OK");
        assertThat(draft.fields().get("currency").status()).isEqualTo("OK");
        assertThat(draft.fields().get("merchant").status()).isEqualTo("OK");
        assertThat(draft.lines()).extracting(ReceiptDraft.Line::amountMinor).containsExactly(129L, 110L, 890L, 249L);
        assertThat(draft.lines()).extracting(ReceiptDraft.Line::position).containsExactly(1, 2, 3, 4);
        assertThat(draft.lines()).allSatisfy(l -> assertThat(l.amountStatus()).isEqualTo("OK"));
        assertThat(draft.findings()).isEmpty();
    }

    @Test
    void BR_RCP_05_an_ambiguous_total_is_never_guessed_and_keeps_its_candidates() {
        Printed printed = new Printed();
        printed.total = Optional.of("1.234");
        printed.decimalSeparator = "unknown";
        printed.language = Optional.empty();
        printed.country = Optional.empty();
        printed.lines = List.of();
        printed.payments = List.of();

        printed.currency = Optional.of("TND");
        CurrencyCode tnd = new CurrencyCode("TND");

        // Three-decimal currency: "1.234" is 1.234 dinars or 1 234 dinars; the parser must not pick one.
        ReceiptDraft draft = ReceiptDraftBuilder.build(printed.build(), tnd, Money.ofMinor(1_000_000_000L, tnd, 3),
                TODAY, raw -> Optional.of(raw));

        assertThat(draft.totalMinor()).isEmpty();
        ReceiptDraft.Field total = draft.fields().get("total");
        assertThat(total.status()).isEqualTo("AMBIGUOUS");
        assertThat(total.raw()).isEqualTo("1.234");
        assertThat(total.candidates()).containsExactlyInAnyOrder("1.234", "1234.000");

        // Two-decimal currency: three digits after the mark can only be a thousands group (BR-RCP-05).
        printed.currency = Optional.of("EUR");
        assertThat(draft(printed).totalMinor()).contains(123_400L);
    }

    @Test
    void BR_RCP_07_a_total_that_differs_from_the_line_items_is_flagged_never_corrected() {
        Printed printed = new Printed();
        printed.total = Optional.of("14,78");
        printed.payments = List.of();

        ReceiptDraft draft = draft(printed);

        assertThat(draft.totalMinor()).contains(1478L);
        assertThat(draft.fields().get("total").raw()).isEqualTo("14,78");
        assertThat(draft.fields().get("total").status()).isNotEqualTo("OK");
        assertThat(draft.findings()).isNotEmpty();
        assertThat(draft.lines()).extracting(ReceiptDraft.Line::amountMinor).containsExactly(129L, 110L, 890L, 249L);
    }

    @Test
    void an_unreadable_total_is_invalid_and_missing_values_are_reported_missing() {
        Printed garbage = new Printed();
        garbage.total = Optional.of("douze euros");
        ReceiptDraft invalid = draft(garbage);
        assertThat(invalid.totalMinor()).isEmpty();
        assertThat(invalid.fields().get("total").status()).isEqualTo("INVALID");

        Printed empty = new Printed();
        empty.total = Optional.empty();
        empty.date = Optional.empty();
        empty.currency = Optional.empty();
        empty.merchant = Optional.of("   ");
        ReceiptDraft missing = draft(empty);
        assertThat(missing.totalMinor()).isEmpty();
        assertThat(missing.purchaseDate()).isEmpty();
        assertThat(missing.detectedCurrency()).isEmpty();
        assertThat(missing.merchantKey()).isEmpty();
        assertThat(List.of("total", "date", "currency", "merchant"))
                .allSatisfy(f -> assertThat(missing.fields().get(f).status()).isEqualTo(ReceiptDraft.MISSING));
    }

    @Test
    void BR_RCP_09_a_foreign_currency_is_flagged_not_converted() {
        Printed printed = new Printed();
        printed.currency = Optional.of("USD");

        ReceiptDraft draft = draft(printed);

        assertThat(draft.detectedCurrency()).contains("USD");
        assertThat(draft.findings()).contains(ParseWarning.CURRENCY_DIFFERS_FROM_HOUSEHOLD.name());
        assertThat(draft.totalMinor()).contains(1378L);
    }

    @Test
    void BR_RCP_08_an_ambiguous_date_is_left_to_the_user() {
        Printed printed = new Printed();
        printed.date = Optional.of("04/05/2026");
        printed.dateOrder = "unknown";
        printed.language = Optional.empty();
        printed.country = Optional.empty();

        ReceiptDraft draft = draft(printed);

        assertThat(draft.purchaseDate()).isEmpty();
        assertThat(draft.fields().get("date").status()).isEqualTo("AMBIGUOUS");
        assertThat(draft.fields().get("date").candidates()).containsExactlyInAnyOrder("2026-05-04", "2026-04-05");
    }

    @Test
    void a_refund_receipt_proposes_a_refund_and_a_quote_is_not_a_proof_of_payment() {
        Printed refund = new Printed();
        refund.documentType = "REFUND_RECEIPT";
        assertThat(draft(refund).proposedKind()).isEqualTo(ProposedKind.REFUND);

        Printed quote = new Printed();
        quote.documentType = "QUOTE";
        assertThat(draft(quote).findings()).contains(ConsistencyFinding.NOT_A_PROOF_OF_PAYMENT.name());
    }

    @Test
    void an_unparseable_line_is_kept_as_printed_without_an_amount() {
        Printed printed = new Printed();
        printed.lines = List.of(line("ITEM", "x".repeat(250), "1,29"), line("DISCOUNT", "REMISE", "??"));
        printed.payments = List.of();
        printed.total = Optional.of("1,29");

        ReceiptDraft draft = draft(printed);

        assertThat(draft.lines().get(0).description()).hasSize(200);
        assertThat(draft.lines().get(1).amountMinor()).isNull();
        assertThat(draft.lines().get(1).amountRaw()).isEqualTo("??");
        assertThat(draft.lines().get(1).amountStatus()).isEqualTo("INVALID");
        assertThat(draft.totalMinor()).contains(129L);
    }
}
