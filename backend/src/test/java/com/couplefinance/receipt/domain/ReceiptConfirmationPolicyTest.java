package com.couplefinance.receipt.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.shared.money.CurrencyCode;
import com.couplefinance.shared.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** Guards of a confirmation (BR-RCP-10, BR-RCP-15) and duplicate signals (BR-RCP-14). */
class ReceiptConfirmationPolicyTest {

    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final Instant NOW = Instant.parse("2026-10-12T08:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 4);

    private static Money eur(String amount) {
        return Money.parse(amount, EUR, 2);
    }

    private static Receipt receipt(ReceiptStatus status, long version) {
        ReceiptPage page = new ReceiptPage(UUID.randomUUID(), 1, "receipts/k", "image/jpeg", 1000, 10, 20,
                new byte[32], 42L);
        return new Receipt(UUID.randomUUID(), new HouseholdId(UUID.randomUUID()), new UserId(UUID.randomUUID()),
                SourceKind.IMAGES, 3000, status, AnalysisStatus.SUCCEEDED, null, 0, NOW, NOW, version, List.of(page));
    }

    private static ReceiptDraft draft(Map<String, String> statuses) {
        Map<String, ReceiptDraft.Field> fields = new LinkedHashMap<>();
        statuses.forEach((name, status) -> fields.put(name, new ReceiptDraft.Field("raw", List.of(), status, null)));
        return new ReceiptDraft(DocumentType.RECEIPT, TaxMode.INCLUSIVE, ProposedKind.EXPENSE, Optional.empty(),
                Optional.of(1378L), Optional.of(DATE), Optional.of("EUR"), fields, List.of(), Set.of());
    }

    private static final Map<String, String> ALL_OK = Map.of("merchant", "OK", "date", "OK", "total", "OK",
            "currency", "OK");

    private static void assertRejected(Runnable action, ReceiptErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApplicationException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    @Test
    void BR_RCP_15_an_open_receipt_in_the_reviewed_version_is_linkable() {
        assertThatCode(() -> ReceiptConfirmationPolicy.requireLinkable(receipt(ReceiptStatus.OPEN, 3), 3))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = ReceiptStatus.class, names = "OPEN", mode = EnumSource.Mode.EXCLUDE)
    void BR_RCP_15_a_receipt_is_confirmed_exactly_once(ReceiptStatus closed) {
        assertRejected(() -> ReceiptConfirmationPolicy.requireLinkable(receipt(closed, 3), 3),
                ReceiptErrorCode.RECEIPT_ALREADY_CONFIRMED);
    }

    @Test
    void a_receipt_that_changed_since_it_was_displayed_must_be_reviewed_again() {
        assertRejected(() -> ReceiptConfirmationPolicy.requireLinkable(receipt(ReceiptStatus.OPEN, 4), 3),
                ReceiptErrorCode.RECEIPT_VERSION_CONFLICT);
    }

    @Test
    void BR_RCP_10_the_acknowledged_total_and_date_are_exactly_the_expense_values() {
        Optional<ReceiptDraft> draft = Optional.of(draft(ALL_OK));
        assertThatCode(() -> ReceiptConfirmationPolicy.requireReviewed(draft, eur("13.78"), DATE, eur("13.78"), DATE,
                Map.of())).doesNotThrowAnyException();

        assertRejected(() -> ReceiptConfirmationPolicy.requireReviewed(draft, eur("13.78"), DATE, eur("13.79"), DATE,
                Map.of()), ReceiptErrorCode.RECEIPT_ACKNOWLEDGEMENT_MISMATCH);
        assertRejected(() -> ReceiptConfirmationPolicy.requireReviewed(draft, eur("13.78"), DATE, eur("13.78"),
                DATE.plusDays(1), Map.of()), ReceiptErrorCode.RECEIPT_ACKNOWLEDGEMENT_MISMATCH);
        assertRejected(() -> ReceiptConfirmationPolicy.requireReviewed(draft, eur("13.78"), DATE,
                Money.parse("13.78", new CurrencyCode("USD"), 2), DATE, Map.of()),
                ReceiptErrorCode.RECEIPT_ACKNOWLEDGEMENT_MISMATCH);
    }

    @Test
    void BR_RCP_10_the_member_may_confirm_a_value_different_from_the_ai_proposal() {
        // The draft says 13,78; the member read 14,78 on the paper: their acknowledged value wins.
        assertThatCode(() -> ReceiptConfirmationPolicy.requireReviewed(Optional.of(draft(ALL_OK)), eur("14.78"), DATE,
                eur("14.78"), DATE, Map.of())).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"AMBIGUOUS", "INVALID", "INCONSISTENT", "NOT_CORROBORATED", "STALE", "MISSING", "LOW_CONFIDENCE"})
    void BR_RCP_10_every_field_whose_status_is_not_OK_must_be_resolved_individually(String status) {
        Map<String, String> statuses = new LinkedHashMap<>(ALL_OK);
        statuses.put("total", status);
        statuses.put("date", status);
        Optional<ReceiptDraft> draft = Optional.of(draft(statuses));

        assertThat(ReceiptConfirmationPolicy.unresolved(draft, Map.of())).containsExactly("date", "total");
        assertRejected(() -> ReceiptConfirmationPolicy.requireReviewed(draft, eur("13.78"), DATE, eur("13.78"), DATE,
                Map.of("total", "13.78")), ReceiptErrorCode.RECEIPT_FIELD_UNRESOLVED);
        assertThatCode(() -> ReceiptConfirmationPolicy.requireReviewed(draft, eur("13.78"), DATE, eur("13.78"), DATE,
                Map.of("total", "13.78", "date", "2026-10-04"))).doesNotThrowAnyException();
    }

    @Test
    void a_receipt_never_analysed_only_needs_the_acknowledgement() {
        assertThat(ReceiptConfirmationPolicy.unresolved(Optional.empty(), Map.of())).isEmpty();
        assertThatCode(() -> ReceiptConfirmationPolicy.requireReviewed(Optional.empty(), eur("9.99"), DATE,
                eur("9.99"), DATE, Map.of())).doesNotThrowAnyException();
    }

    @Test
    void resolutions_are_bounded_values_of_known_fields() {
        Optional<ReceiptDraft> draft = Optional.of(draft(ALL_OK));
        assertRejected(() -> ReceiptConfirmationPolicy.requireReviewed(draft, eur("1.00"), DATE, eur("1.00"), DATE,
                Map.of("ownerUserId", "x")), ReceiptErrorCode.RECEIPT_RESOLUTION_INVALID);
        assertRejected(() -> ReceiptConfirmationPolicy.requireReviewed(draft, eur("1.00"), DATE, eur("1.00"), DATE,
                Map.of("merchant", "x".repeat(201))), ReceiptErrorCode.RECEIPT_RESOLUTION_INVALID);
    }

    @Test
    void BR_RCP_14_perceptual_hashes_a_few_bits_apart_are_the_same_paper() {
        long hash = 0x0F0F_F0F0_1234_5678L;
        assertThat(DuplicateSignals.distance(hash, hash)).isZero();
        assertThat(DuplicateSignals.similarImage(hash, hash ^ 0b111111L)).isTrue();
        assertThat(DuplicateSignals.similarImage(hash, hash ^ 0b1111111L)).isFalse();
        assertThat(DuplicateSignals.similarImage(hash, ~hash)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "carrefour, carrefour, true",
            "carrefour market, carrefour, false", // 10 shared trigrams of 17: 0.59, just under the threshold
            "carrefour, carrefour city, true",
            "carrefour, monoprix, false",
            "carrefour, '', false",
            "sncf, sncf connect voyages internet, false",
    })
    void BR_RCP_14_merchants_match_when_equal_or_trigram_similar(String a, String b, boolean same) {
        assertThat(DuplicateSignals.sameMerchant(a, b)).isEqualTo(same);
        assertThat(DuplicateSignals.sameMerchant(b, a)).isEqualTo(same);
    }

    @Test
    void trigram_similarity_follows_pg_trgm() {
        assertThat(DuplicateSignals.trigramSimilarity("word", "word")).isEqualTo(1.0);
        // pg_trgm: similarity('word', 'two words') = 0.363636
        assertThat(DuplicateSignals.trigramSimilarity("word", "two words")).isBetween(0.36, 0.37);
        assertThat(DuplicateSignals.trigramSimilarity("abc", "xyz")).isZero();
        assertThat(DuplicateSignals.trigramSimilarity("", "abc")).isZero();
        assertThat(DuplicateSignals.sameMerchant(null, "abc")).isFalse();
    }
}
