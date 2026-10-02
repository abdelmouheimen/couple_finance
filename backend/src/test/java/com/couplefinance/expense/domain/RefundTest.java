package com.couplefinance.expense.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.couplefinance.household.api.LedgerProfile;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.shared.money.CurrencyCode;
import com.couplefinance.shared.money.Money;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/** Refund rules of the Expense aggregate (BR-EXP-02, BR-EXP-03, BR-EXP-08, BR-MON-06), fixed clock. */
class RefundTest {

    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC);
    private static final LedgerProfile LEDGER =
            new LedgerProfile(EUR, ZoneId.of("Europe/Paris"), 2, Money.ofMinor(100_000_000, EUR, 2));
    private static final LocalDate ORIGINAL_DATE = LocalDate.of(2026, 9, 20);

    private final HouseholdId household = new HouseholdId(UUID.randomUUID());
    private final UserId user = new UserId(UUID.randomUUID());
    private final UUID groceries = UUID.randomUUID();
    private final UUID housing = UUID.randomUUID();

    private static Money eur(String amount) {
        return Money.parse(amount, EUR, 2);
    }

    private Expense original(SharingType sharing, long... itemMinors) {
        long total = 0;
        List<NewItem> items = new ArrayList<>();
        for (long minor : itemMinors) {
            total += minor;
            items.add(new NewItem(UUID.randomUUID(), Money.ofMinor(minor, EUR, 2), null));
        }
        return Expense.create(household, LEDGER, CLOCK, Money.ofMinor(total, EUR, 2), ORIGINAL_DATE, items, user,
                sharing, user, null, null);
    }

    private Expense refund(Expense original, String amount, LocalDate date, SharingType sharing,
            String alreadyRefunded) {
        Money money = eur(amount);
        return Expense.createRefund(household, LEDGER, CLOCK, money, date, original.proportionalItems(money), user,
                sharing, user, null, null, original, eur(alreadyRefunded));
    }

    private static void assertRejected(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApplicationException.class,
                e -> assertThat(e.errorCode().code()).isEqualTo(code));
    }

    @Test
    void BR_EXP_02_a_refund_is_a_positive_amount_of_kind_refund_linked_to_its_original() {
        Expense original = original(SharingType.SHARED, 1000);

        Expense refund = refund(original, "4.00", ORIGINAL_DATE, SharingType.SHARED, "0.00");

        assertThat(refund.kind()).isEqualTo(ExpenseKind.REFUND);
        assertThat(refund.amountMinor()).isEqualTo(400);
        assertThat(refund.refundOfExpenseId()).isEqualTo(original.id());
    }

    @Test
    void BR_EXP_03_refund_sum_cannot_exceed_original() {
        Expense original = original(SharingType.SHARED, 1000);

        assertThat(refund(original, "4.00", ORIGINAL_DATE, SharingType.SHARED, "6.00").amountMinor()).isEqualTo(400);
        assertRejected(() -> refund(original, "4.01", ORIGINAL_DATE, SharingType.SHARED, "6.00"),
                "EXPENSE_REFUND_EXCEEDS_ORIGINAL");
        assertRejected(() -> refund(original, "10.01", ORIGINAL_DATE, SharingType.SHARED, "0.00"),
                "EXPENSE_REFUND_EXCEEDS_ORIGINAL");
    }

    @Test
    void BR_EXP_03_refund_has_same_visibility() {
        Expense shared = original(SharingType.SHARED, 1000);
        Expense personal = original(SharingType.PERSONAL, 1000);

        assertRejected(() -> refund(shared, "1.00", ORIGINAL_DATE, SharingType.PERSONAL, "0.00"),
                "EXPENSE_REFUND_VISIBILITY_MISMATCH");
        assertRejected(() -> refund(personal, "1.00", ORIGINAL_DATE, SharingType.SHARED, "0.00"),
                "EXPENSE_REFUND_VISIBILITY_MISMATCH");
        Expense refund = refund(personal, "1.00", ORIGINAL_DATE, SharingType.PERSONAL, "0.00");
        assertThat(refund.ownerUserId()).isEqualTo(user.value());
    }

    @Test
    void BR_EXP_03_refund_date_not_before_original() {
        Expense original = original(SharingType.SHARED, 1000);

        assertRejected(() -> refund(original, "1.00", ORIGINAL_DATE.minusDays(1), SharingType.SHARED, "0.00"),
                "EXPENSE_REFUND_DATE_BEFORE_ORIGINAL");
        assertThat(refund(original, "1.00", ORIGINAL_DATE, SharingType.SHARED, "0.00").expenseDate())
                .isEqualTo(ORIGINAL_DATE);
    }

    @Test
    void BR_EXP_03_a_refund_cannot_be_the_original_of_another_refund() {
        Expense original = original(SharingType.SHARED, 1000);
        Expense first = refund(original, "4.00", ORIGINAL_DATE, SharingType.SHARED, "0.00");

        assertRejected(() -> Expense.createRefund(household, LEDGER, CLOCK, eur("1.00"), ORIGINAL_DATE,
                List.of(new NewItem(groceries, eur("1.00"), null)), user, SharingType.SHARED, user, null, null,
                first, eur("0.00")), "EXPENSE_REFUND_ORIGINAL_INVALID");
    }

    @Test
    void BR_EXP_03_an_original_of_another_household_is_rejected() {
        Expense original = original(SharingType.SHARED, 1000);
        HouseholdId other = new HouseholdId(UUID.randomUUID());

        assertRejected(() -> Expense.createRefund(other, LEDGER, CLOCK, eur("1.00"), ORIGINAL_DATE,
                List.of(new NewItem(groceries, eur("1.00"), null)), user, SharingType.SHARED, user, null, null,
                original, eur("0.00")), "EXPENSE_REFUND_ORIGINAL_INVALID");
    }

    @Test
    void BR_EXP_03_BR_MON_06_default_items_follow_the_original_proportionally_with_remainder_first() {
        Expense original = Expense.create(household, LEDGER, CLOCK, eur("10.00"), ORIGINAL_DATE,
                List.of(new NewItem(groceries, eur("7.00"), null), new NewItem(housing, eur("3.00"), null)), user,
                SharingType.SHARED, user, null, null);

        List<NewItem> items = original.proportionalItems(eur("0.05"));

        // 5 * 700/1000 = 3.5 -> 3 ; 5 * 300/1000 = 1.5 -> 1 ; remainder 1 goes to the first item
        assertThat(items).extracting(NewItem::categoryId).containsExactly(groceries, housing);
        assertThat(items).extracting(item -> item.amount().minorUnits()).containsExactly(4L, 1L);
    }

    @Test
    void BR_EXP_08_default_items_drop_categories_whose_share_is_zero() {
        Expense original = original(SharingType.SHARED, 1, 1, 998);

        List<NewItem> items = original.proportionalItems(Money.ofMinor(1, EUR, 2));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).amount().minorUnits()).isEqualTo(1L);
    }

    @Property
    void BR_EXP_03_BR_MON_06_default_items_always_sum_exactly_and_are_positive(
            @ForAll("itemMinors") List<Long> itemMinors, @ForAll("ratio") int ratioPerMille) {
        long[] minors = itemMinors.stream().mapToLong(Long::longValue).toArray();
        long total = java.util.Arrays.stream(minors).sum();
        Expense original = original(SharingType.SHARED, minors);
        long refundMinor = Math.max(1, Math.min(total, total * ratioPerMille / 1000));
        Money amount = Money.ofMinor(refundMinor, EUR, 2);

        List<NewItem> items = original.proportionalItems(amount);

        assertThat(items).isNotEmpty().hasSizeLessThanOrEqualTo(minors.length);
        assertThat(items).allSatisfy(item -> assertThat(item.amount().minorUnits()).isPositive());
        assertThat(items.stream().mapToLong(item -> item.amount().minorUnits()).sum()).isEqualTo(refundMinor);
        assertThat(items.stream().map(NewItem::categoryId).distinct().count()).isEqualTo(items.size());
        // a refund built from them satisfies BR-EXP-08
        assertThat(Expense.createRefund(household, LEDGER, CLOCK, amount, ORIGINAL_DATE, items, user,
                SharingType.SHARED, user, null, null, original, Money.zero(EUR, 2)).items()).hasSize(items.size());
    }

    @Provide
    Arbitrary<List<Long>> itemMinors() {
        return Arbitraries.longs().between(1, 5_000_000).list().ofMinSize(1).ofMaxSize(Expense.MAX_ITEMS);
    }

    @Provide
    Arbitrary<Integer> ratio() {
        return Arbitraries.integers().between(1, 1000);
    }
}
