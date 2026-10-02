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

/** Edit rules of the Expense aggregate (BR-EXP-03, 05..09, 14, MON-07), fixed clock. */
class ExpenseUpdateTest {

    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC);
    private static final LedgerProfile LEDGER =
            new LedgerProfile(EUR, ZoneId.of("Europe/Paris"), 2, Money.ofMinor(100_000_000, EUR, 2));
    private static final LocalDate DATE = LocalDate.of(2026, 9, 20);

    private final HouseholdId household = new HouseholdId(UUID.randomUUID());
    private final UserId payer = new UserId(UUID.randomUUID());
    private final UserId partner = new UserId(UUID.randomUUID());
    private final UUID groceries = UUID.randomUUID();
    private final UUID housing = UUID.randomUUID();

    private static Money eur(String amount) {
        return Money.parse(amount, EUR, 2);
    }

    private NewItem item(UUID category, String amount) {
        return new NewItem(category, eur(amount), null);
    }

    private Expense expense(SharingType sharing) {
        return Expense.create(household, LEDGER, CLOCK, eur("10.00"), DATE,
                List.of(item(groceries, "6.00"), item(housing, "4.00")), payer, sharing, payer, "Shop", "note");
    }

    private boolean edit(Expense expense, UserId editor, String amount, LocalDate date, List<NewItem> items,
            UserId paidBy, SharingType sharing) {
        return expense.update(editor, LEDGER, CLOCK, eur(amount), date, items, paidBy, sharing, "Shop", "note",
                Expense.RefundState.none());
    }

    private static void assertRejected(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApplicationException.class,
                e -> assertThat(e.errorCode().code()).isEqualTo(code));
    }

    @Test
    void BR_EXP_09_any_member_can_edit_a_shared_expense() {
        Expense expense = expense(SharingType.SHARED);

        boolean changed = edit(expense, partner, "12.00", DATE,
                List.of(item(groceries, "8.00"), item(housing, "4.00")), payer, SharingType.SHARED);

        assertThat(changed).isTrue();
        assertThat(expense.amountMinor()).isEqualTo(1200);
        assertThat(expense.updatedBy()).isEqualTo(partner.value());
        assertThat(expense.items()).extracting(ExpenseItem::amountMinor).containsExactly(800L, 400L);
    }

    @Test
    void BR_EXP_09_only_the_owner_can_edit_a_personal_expense() {
        Expense expense = expense(SharingType.PERSONAL);

        assertRejected(() -> edit(expense, partner, "10.00", DATE,
                List.of(item(groceries, "10.00")), payer, SharingType.PERSONAL), "EXPENSE_NOT_FOUND");
        assertThat(edit(expense, payer, "10.00", DATE, List.of(item(groceries, "10.00")), payer,
                SharingType.PERSONAL)).isTrue();
    }

    @Test
    void BR_EXP_09_only_payer_can_switch_sharing() {
        Expense paidByPartner = Expense.create(household, LEDGER, CLOCK, eur("10.00"), DATE,
                List.of(item(groceries, "10.00")), partner, SharingType.SHARED, payer, null, null);

        assertRejected(() -> edit(paidByPartner, payer, "10.00", DATE, List.of(item(groceries, "10.00")), partner,
                SharingType.PERSONAL), "EXPENSE_SHARING_CHANGE_FORBIDDEN");

        Expense paidByMe = expense(SharingType.SHARED);
        assertThat(edit(paidByMe, payer, "10.00", DATE, List.of(item(groceries, "10.00")), payer,
                SharingType.PERSONAL)).isTrue();
        assertThat(paidByMe.sharingType()).isEqualTo(SharingType.PERSONAL);
        assertThat(paidByMe.ownerUserId()).isEqualTo(payer.value());
    }

    @Test
    void BR_EXP_07_a_personal_expense_stays_paid_by_its_creator() {
        Expense expense = expense(SharingType.SHARED);

        assertRejected(() -> edit(expense, payer, "10.00", DATE, List.of(item(groceries, "10.00")), partner,
                SharingType.PERSONAL), "EXPENSE_PERSONAL_PAYER_MISMATCH");
        Expense byPartner = Expense.create(household, LEDGER, CLOCK, eur("10.00"), DATE,
                List.of(item(groceries, "10.00")), payer, SharingType.SHARED, partner, null, null);
        assertRejected(() -> edit(byPartner, payer, "10.00", DATE, List.of(item(groceries, "10.00")), payer,
                SharingType.PERSONAL), "EXPENSE_PERSONAL_PAYER_MISMATCH");
    }

    @Test
    void BR_EXP_08_items_must_still_sum_to_the_amount() {
        Expense expense = expense(SharingType.SHARED);

        assertRejected(() -> edit(expense, payer, "11.00", DATE,
                List.of(item(groceries, "6.00"), item(housing, "4.00")), payer, SharingType.SHARED),
                "EXPENSE_ITEMS_SUM_MISMATCH");
    }

    @Test
    void BR_MON_07_amount_must_be_positive_and_within_the_maximum() {
        Expense expense = expense(SharingType.SHARED);

        assertRejected(() -> edit(expense, payer, "0.00", DATE, List.of(item(groceries, "0.00")), payer,
                SharingType.SHARED), "AMOUNT_NOT_POSITIVE");
    }

    @Test
    void BR_EXP_06_the_date_window_is_checked_only_when_the_date_changes() {
        Expense old = Expense.create(household, LEDGER, CLOCK, eur("10.00"), LocalDate.of(2021, 10, 2),
                List.of(item(groceries, "10.00")), payer, SharingType.SHARED, payer, null, null);
        Clock later = Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC);

        // now older than 5 years, but the date is not touched
        assertThat(old.update(payer, LEDGER, later, eur("10.00"), LocalDate.of(2021, 10, 2),
                List.of(item(groceries, "10.00")), payer, SharingType.SHARED, "Changed", null,
                Expense.RefundState.none())).isTrue();
        assertRejected(() -> old.update(payer, LEDGER, later, eur("10.00"), LocalDate.of(2021, 10, 3),
                List.of(item(groceries, "10.00")), payer, SharingType.SHARED, "Changed", null,
                Expense.RefundState.none()), "EXPENSE_DATE_OUT_OF_RANGE");
    }

    @Test
    void BR_EXP_14_unchanged_archived_category_stays_valid() {
        Expense expense = expense(SharingType.SHARED);
        UUID fresh = UUID.randomUUID();

        // groceries 6.00 and housing 4.00 are unchanged: nothing needs an active category
        assertThat(expense.categoriesRequiringActiveStatus(
                List.of(item(groceries, "6.00"), item(housing, "4.00")))).isEmpty();
        // a changed amount or a new category must be active
        assertThat(expense.categoriesRequiringActiveStatus(
                List.of(item(groceries, "7.00"), item(fresh, "3.00")))).containsExactlyInAnyOrder(groceries, fresh);
        assertThat(expense.categoriesRequiringActiveStatus(
                List.of(new NewItem(groceries, eur("6.00"), "Fruit"), item(housing, "4.00"))))
                .containsExactly(groceries);
    }

    @Test
    void BR_EXP_10_an_edit_without_change_touches_nothing() {
        Expense expense = expense(SharingType.SHARED);
        Instant updatedAt = expense.updatedAt();
        Clock later = Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC);

        boolean changed = expense.update(payer, LEDGER, later, eur("10.00"), DATE,
                List.of(item(groceries, "6.00"), item(housing, "4.00")), payer, SharingType.SHARED, "Shop", "note",
                Expense.RefundState.none());

        assertThat(changed).isFalse();
        assertThat(expense.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void BR_EXP_10_the_snapshot_keeps_the_old_values_for_the_audit_diff() {
        Expense expense = expense(SharingType.SHARED);
        ExpenseSnapshot before = expense.snapshot();

        edit(expense, payer, "10.00", DATE, List.of(item(groceries, "10.00")), payer, SharingType.SHARED);

        assertThat(before.items()).hasSize(2);
        assertThat(expense.snapshot().items()).hasSize(1);
        assertThat(expense.snapshot()).isNotEqualTo(before);
    }

    @Test
    void BR_EXP_03_a_linked_refund_stays_within_its_original_after_an_edit() {
        Expense original = expense(SharingType.SHARED);
        Expense refund = Expense.createRefund(household, LEDGER, CLOCK, eur("4.00"), DATE,
                original.proportionalItems(eur("4.00")), payer, SharingType.SHARED, payer, null, null, original,
                eur("0.00"));
        List<NewItem> items = original.proportionalItems(eur("5.00"));

        // others refunded 5.00 + this 5.00 = 10.00: fine; 5.01 is not
        assertThat(refund.update(payer, LEDGER, CLOCK, eur("5.00"), DATE, items, payer, SharingType.SHARED, null,
                null, new Expense.RefundState(original, 500, 0, null))).isTrue();
        assertRejected(() -> refund.update(payer, LEDGER, CLOCK, eur("5.01"), DATE,
                original.proportionalItems(eur("5.01")), payer, SharingType.SHARED, null, null,
                new Expense.RefundState(original, 500, 0, null)), "EXPENSE_REFUND_EXCEEDS_ORIGINAL");
        assertRejected(() -> refund.update(payer, LEDGER, CLOCK, eur("4.00"), DATE.minusDays(1),
                original.proportionalItems(eur("4.00")), payer, SharingType.SHARED, null, null,
                new Expense.RefundState(original, 0, 0, null)), "EXPENSE_REFUND_DATE_BEFORE_ORIGINAL");
        assertRejected(() -> refund.update(payer, LEDGER, CLOCK, eur("4.00"), DATE,
                original.proportionalItems(eur("4.00")), payer, SharingType.PERSONAL, null, null,
                new Expense.RefundState(original, 0, 0, null)), "EXPENSE_REFUND_VISIBILITY_MISMATCH");
    }

    @Test
    void BR_EXP_03_an_original_keeps_its_live_refunds_valid_after_an_edit() {
        Expense original = expense(SharingType.SHARED);
        Expense.RefundState refunds = new Expense.RefundState(null, 0, 400, DATE.plusDays(2));
        List<NewItem> items = List.of(item(groceries, "3.00"));

        assertRejected(() -> original.update(payer, LEDGER, CLOCK, eur("3.00"), DATE, items, payer,
                SharingType.SHARED, null, null, refunds), "EXPENSE_REFUND_EXCEEDS_ORIGINAL");
        assertRejected(() -> original.update(payer, LEDGER, CLOCK, eur("10.00"), DATE.plusDays(3),
                List.of(item(groceries, "10.00")), payer, SharingType.SHARED, null, null, refunds),
                "EXPENSE_REFUND_DATE_BEFORE_ORIGINAL");
        assertRejected(() -> original.update(payer, LEDGER, CLOCK, eur("10.00"), DATE,
                List.of(item(groceries, "10.00")), payer, SharingType.PERSONAL, null, null, refunds),
                "EXPENSE_REFUND_VISIBILITY_MISMATCH");
    }
}
