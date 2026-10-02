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

/** Logical deletion and restoration (BR-EXP-03, BR-EXP-11), deterministic clocks. */
class ExpenseDeleteRestoreTest {

    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final Instant CREATED = Instant.parse("2026-10-01T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(CREATED, ZoneOffset.UTC);
    private static final LedgerProfile LEDGER =
            new LedgerProfile(EUR, ZoneId.of("Europe/Paris"), 2, Money.ofMinor(100_000_000, EUR, 2));
    private static final LocalDate DATE = LocalDate.of(2026, 9, 20);

    private final HouseholdId household = new HouseholdId(UUID.randomUUID());
    private final UserId user = new UserId(UUID.randomUUID());

    private static Money eur(String amount) {
        return Money.parse(amount, EUR, 2);
    }

    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private Expense expense(String amount) {
        return Expense.create(household, LEDGER, CLOCK, eur(amount), DATE,
                List.of(new NewItem(UUID.randomUUID(), eur(amount), null)), user, SharingType.SHARED, user, null,
                null);
    }

    private Expense refundOf(Expense original, String amount) {
        Money money = eur(amount);
        return Expense.createRefund(household, LEDGER, CLOCK, money, DATE, original.proportionalItems(money), user,
                SharingType.SHARED, user, null, null, original, eur("0.00"));
    }

    private static void assertRejected(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApplicationException.class,
                e -> assertThat(e.errorCode().code()).isEqualTo(code));
    }

    @Test
    void BR_EXP_11_delete_marks_the_expense_deleted_by_the_actor() {
        Expense expense = expense("10.00");

        expense.delete(user, at("2026-10-02T08:00:00Z"), false);

        assertThat(expense.deletedAt()).isEqualTo(Instant.parse("2026-10-02T08:00:00Z"));
        assertThat(expense.deletedBy()).isEqualTo(user.value());
        assertThat(expense.updatedBy()).isEqualTo(user.value());
    }

    @Test
    void BR_EXP_03_cannot_delete_expense_with_live_refunds() {
        Expense expense = expense("10.00");

        assertRejected(() -> expense.delete(user, CLOCK, true), "EXPENSE_HAS_LIVE_REFUNDS");
        assertThat(expense.deletedAt()).isNull();
    }

    @Test
    void BR_EXP_11_restore_within_90_days() {
        Expense expense = expense("10.00");
        expense.delete(user, at("2026-10-02T08:00:00Z"), false);

        expense.restore(user, at("2026-12-31T08:00:00Z"), LEDGER, null, 0); // exactly 90 days later

        assertThat(expense.deletedAt()).isNull();
        assertThat(expense.deletedBy()).isNull();
    }

    @Test
    void BR_EXP_11_restore_after_90_days_is_rejected_as_not_found() {
        Expense expense = expense("10.00");
        expense.delete(user, at("2026-10-02T08:00:00Z"), false);

        assertRejected(() -> expense.restore(user, at("2026-12-31T08:00:00.001Z"), LEDGER, null, 0),
                "EXPENSE_NOT_FOUND");
        assertThat(expense.deletedAt()).isNotNull();
    }

    @Test
    void a_live_expense_cannot_be_restored_nor_a_deleted_one_deleted_again() {
        Expense expense = expense("10.00");
        assertRejected(() -> expense.restore(user, CLOCK, LEDGER, null, 0), "EXPENSE_NOT_FOUND");
        expense.delete(user, CLOCK, false);
        assertRejected(() -> expense.delete(user, CLOCK, false), "EXPENSE_NOT_FOUND");
    }

    @Test
    void BR_EXP_03_restoring_a_refund_rechecks_the_original() {
        Expense original = expense("10.00");
        Expense refund = refundOf(original, "6.00");
        refund.delete(user, CLOCK, false);

        // the original is deleted
        assertRejected(() -> refund.restore(user, CLOCK, LEDGER, null, 0), "EXPENSE_REFUND_ORIGINAL_INVALID");
        // another live refund of 5.00 now exists: 5.00 + 6.00 > 10.00
        assertRejected(() -> refund.restore(user, CLOCK, LEDGER, original, 500), "EXPENSE_REFUND_EXCEEDS_ORIGINAL");
        assertThat(refund.deletedAt()).isNotNull();
        // 4.00 + 6.00 = 10.00: allowed
        refund.restore(user, CLOCK, LEDGER, original, 400);
        assertThat(refund.deletedAt()).isNull();
    }
}
