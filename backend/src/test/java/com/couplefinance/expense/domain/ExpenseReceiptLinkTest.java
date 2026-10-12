package com.couplefinance.expense.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.couplefinance.household.api.LedgerProfile;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.shared.money.CurrencyCode;
import com.couplefinance.shared.money.Money;
import org.junit.jupiter.api.Test;

/** The single link Expense → Receipt (ADR-006, BR-RCP-15): a receipt never changes an expense. */
class ExpenseReceiptLinkTest {

    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC);
    private static final LedgerProfile LEDGER =
            new LedgerProfile(EUR, ZoneId.of("Europe/Paris"), 2, Money.ofMinor(100_000_000, EUR, 2));

    private final UserId payer = new UserId(UUID.randomUUID());
    private final UserId partner = new UserId(UUID.randomUUID());

    private Expense expense(SharingType sharing) {
        Money amount = Money.parse("13.78", EUR, 2);
        return Expense.create(new HouseholdId(UUID.randomUUID()), LEDGER, CLOCK, amount, LocalDate.of(2026, 9, 20),
                List.of(new NewItem(UUID.randomUUID(), amount, null)), payer, sharing, payer, "Carrefour", null);
    }

    @Test
    void BR_RCP_02_an_expense_confirmed_from_a_receipt_keeps_its_exact_values_and_its_source() {
        Expense expense = expense(SharingType.SHARED);
        UUID receipt = UUID.randomUUID();

        expense.confirmedFrom(receipt);

        assertThat(expense.source()).isEqualTo(ExpenseSource.RECEIPT);
        assertThat(expense.receiptId()).isEqualTo(receipt);
        assertThat(expense.amountMinor()).isEqualTo(1378L);
        assertThatThrownBy(() -> expense.confirmedFrom(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_manual_expense_has_no_receipt() {
        Expense expense = expense(SharingType.SHARED);
        assertThat(expense.source()).isEqualTo(ExpenseSource.MANUAL);
        assertThat(expense.receiptId()).isNull();
    }

    @Test
    void BR_RCP_14_attaching_a_receipt_changes_nothing_but_the_link() {
        Expense expense = expense(SharingType.SHARED);
        UUID receipt = UUID.randomUUID();

        expense.attachReceipt(receipt, partner);

        assertThat(expense.receiptId()).isEqualTo(receipt);
        assertThat(expense.source()).isEqualTo(ExpenseSource.MANUAL);
        assertThat(expense.amountMinor()).isEqualTo(1378L);
        assertThat(expense.merchantDisplay()).isEqualTo("Carrefour");
    }

    @Test
    void BR_RCP_15_an_expense_holds_at_most_one_receipt() {
        Expense expense = expense(SharingType.SHARED);
        UUID first = UUID.randomUUID();
        expense.attachReceipt(first, payer);

        assertThatThrownBy(() -> expense.attachReceipt(UUID.randomUUID(), payer))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.errorCode())
                        .isEqualTo(ExpenseErrorCode.EXPENSE_RECEIPT_ALREADY_LINKED));
        assertThat(expense.receiptId()).isEqualTo(first);
    }

    @Test
    void BR_EXP_09_a_receipt_cannot_be_attached_to_the_partners_personal_expense() {
        Expense personal = expense(SharingType.PERSONAL);

        assertThatThrownBy(() -> personal.attachReceipt(UUID.randomUUID(), partner))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.errorCode())
                        .isEqualTo(ExpenseErrorCode.EXPENSE_NOT_FOUND));
        assertThat(personal.receiptId()).isNull();
        personal.attachReceipt(UUID.randomUUID(), payer);
        assertThat(personal.receiptId()).isNotNull();
    }
}
