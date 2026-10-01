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
import org.junit.jupiter.api.Test;

/** Creation invariants of the Expense aggregate, with a fixed clock (BR-EXP-01, 05..08, BR-MON-07). */
class ExpenseTest {

    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    /** 2026-10-01 23:30 UTC is already 2026-10-02 01:30 in Paris. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T23:30:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY_IN_PARIS = LocalDate.of(2026, 10, 2);
    private static final LedgerProfile LEDGER = new LedgerProfile(EUR, PARIS, 2, Money.ofMinor(100_000_000, EUR, 2));

    private final HouseholdId household = new HouseholdId(UUID.randomUUID());
    private final UserId creator = new UserId(UUID.randomUUID());
    private final UUID groceries = UUID.randomUUID();
    private final UUID housing = UUID.randomUUID();

    private static Money eur(String amount) {
        return Money.parse(amount, EUR, 2);
    }

    private NewItem item(UUID category, String amount) {
        return new NewItem(category, eur(amount), null);
    }

    private Expense create(String amount, LocalDate date, List<NewItem> items, SharingType sharing, UserId paidBy) {
        return Expense.create(household, LEDGER, CLOCK, eur(amount), date, items, paidBy, sharing, creator, null,
                null);
    }

    private Expense createShared(String amount, List<NewItem> items) {
        return create(amount, TODAY_IN_PARIS, items, SharingType.SHARED, creator);
    }

    private static void assertRejected(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApplicationException.class,
                e -> assertThat(e.errorCode().code()).isEqualTo(code));
    }

    // ------------------------------------------------------------------ BR-EXP-01 / BR-EXP-02

    @Test
    void BR_EXP_02_a_created_expense_is_an_expense_with_minor_unit_amounts_and_positions() {
        Expense expense = createShared("12.50", List.of(item(groceries, "10.00"), item(housing, "2.50")));

        assertThat(expense.kind()).isEqualTo(ExpenseKind.EXPENSE);
        assertThat(expense.amountMinor()).isEqualTo(1250L);
        assertThat(expense.currency()).isEqualTo(EUR);
        assertThat(expense.items()).extracting(ExpenseItem::position).containsExactly(1, 2);
        assertThat(expense.items()).extracting(ExpenseItem::amountMinor).containsExactly(1000L, 250L);
        assertThat(expense.createdBy()).isEqualTo(creator.value());
        assertThat(expense.createdAt()).isEqualTo(CLOCK.instant());
        assertThat(expense.version()).isZero();
    }

    // ------------------------------------------------------------------ BR-EXP-05 / BR-MON-07

    @Test
    void BR_EXP_05_currency_must_equal_the_household_currency() {
        Money usd = Money.parse("5.00", new CurrencyCode("USD"), 2);

        assertRejected(() -> Expense.create(household, LEDGER, CLOCK, usd, TODAY_IN_PARIS,
                List.of(new NewItem(groceries, usd, null)), creator, SharingType.SHARED, creator, null, null),
                "CURRENCY_MISMATCH");
    }

    @Test
    void BR_MON_07_amount_is_strictly_positive_and_within_the_maximum() {
        assertRejected(() -> createShared("0.00", List.of(item(groceries, "0.00"))), "AMOUNT_NOT_POSITIVE");
        assertRejected(() -> createShared("-1.00", List.of(item(groceries, "-1.00"))), "AMOUNT_NOT_POSITIVE");
        assertRejected(() -> createShared("1000000.01", List.of(item(groceries, "1000000.01"))),
                "AMOUNT_EXCEEDS_MAXIMUM");
        assertThat(createShared("1000000.00", List.of(item(groceries, "1000000.00"))).amountMinor())
                .isEqualTo(100_000_000L);
    }

    // ------------------------------------------------------------------ BR-EXP-06

    @Test
    void BR_EXP_06_date_window_uses_today_in_the_household_timezone() {
        // "today" is 2026-10-02 in Paris although it is still 2026-10-01 in UTC.
        assertThat(create("5.00", TODAY_IN_PARIS.plusDays(1), List.of(item(groceries, "5.00")), SharingType.SHARED,
                creator).expenseDate()).isEqualTo(LocalDate.of(2026, 10, 3));
        assertRejected(() -> create("5.00", TODAY_IN_PARIS.plusDays(2), List.of(item(groceries, "5.00")),
                SharingType.SHARED, creator), "EXPENSE_DATE_OUT_OF_RANGE");
    }

    @Test
    void BR_EXP_06_date_window_five_years_back_is_inclusive() {
        LocalDate oldest = TODAY_IN_PARIS.minusYears(5);

        assertThat(create("5.00", oldest, List.of(item(groceries, "5.00")), SharingType.SHARED, creator)
                .expenseDate()).isEqualTo(oldest);
        assertRejected(() -> create("5.00", oldest.minusDays(1), List.of(item(groceries, "5.00")),
                SharingType.SHARED, creator), "EXPENSE_DATE_OUT_OF_RANGE");
    }

    // ------------------------------------------------------------------ BR-EXP-07

    @Test
    void BR_EXP_07_a_personal_expense_is_owned_by_its_creator() {
        Expense personal = create("5.00", TODAY_IN_PARIS, List.of(item(groceries, "5.00")), SharingType.PERSONAL,
                creator);

        assertThat(personal.sharingType()).isEqualTo(SharingType.PERSONAL);
        assertThat(personal.ownerUserId()).isEqualTo(creator.value());
        assertThat(personal.paidByUserId()).isEqualTo(creator.value());
    }

    @Test
    void BR_EXP_07_a_shared_expense_has_no_owner_and_may_be_paid_by_the_partner() {
        UserId partner = new UserId(UUID.randomUUID());

        Expense shared = create("5.00", TODAY_IN_PARIS, List.of(item(groceries, "5.00")), SharingType.SHARED,
                partner);

        assertThat(shared.sharingType()).isEqualTo(SharingType.SHARED);
        assertThat(shared.ownerUserId()).isNull();
        assertThat(shared.paidByUserId()).isEqualTo(partner.value());
    }

    @Test
    void BR_EXP_07_a_personal_expense_cannot_be_paid_by_someone_else() {
        UserId partner = new UserId(UUID.randomUUID());

        assertRejected(() -> create("5.00", TODAY_IN_PARIS, List.of(item(groceries, "5.00")), SharingType.PERSONAL,
                partner), "EXPENSE_PERSONAL_PAYER_MISMATCH");
    }

    // ------------------------------------------------------------------ BR-EXP-08

    @Test
    void BR_EXP_08_expense_items_must_sum_to_amount() {
        assertRejected(() -> createShared("12.50", List.of(item(groceries, "10.00"), item(housing, "2.49"))),
                "EXPENSE_ITEMS_SUM_MISMATCH");
        assertRejected(() -> createShared("12.50", List.of(item(groceries, "10.00"), item(housing, "2.51"))),
                "EXPENSE_ITEMS_SUM_MISMATCH");
        assertThat(createShared("12.50", List.of(item(groceries, "10.00"), item(housing, "2.50"))).items())
                .hasSize(2);
    }

    @Test
    void BR_EXP_08_one_to_ten_items() {
        assertRejected(() -> createShared("5.00", List.of()), "EXPENSE_ITEM_COUNT_INVALID");
        List<NewItem> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(item(UUID.randomUUID(), "1.00"));
        }
        assertRejected(() -> createShared("11.00", eleven), "EXPENSE_ITEM_COUNT_INVALID");
        assertThat(createShared("10.00", eleven.subList(0, 10)).items()).hasSize(10);
    }

    @Test
    void BR_EXP_08_at_most_one_item_per_category() {
        assertRejected(() -> createShared("10.00", List.of(item(groceries, "5.00"), item(groceries, "5.00"))),
                "EXPENSE_ITEM_DUPLICATE_CATEGORY");
    }

    @Test
    void BR_EXP_08_each_item_is_strictly_positive() {
        assertRejected(() -> createShared("5.00", List.of(item(groceries, "6.00"), item(housing, "-1.00"))),
                "AMOUNT_NOT_POSITIVE");
        assertRejected(() -> createShared("5.00", List.of(item(groceries, "5.00"), item(housing, "0.00"))),
                "AMOUNT_NOT_POSITIVE");
    }

    @Test
    void BR_EXP_08_even_split_items_follow_BR_MON_06() {
        List<Money> shares = eur("10.00").split(3);
        List<NewItem> items = new ArrayList<>();
        for (Money share : shares) {
            items.add(new NewItem(UUID.randomUUID(), share, null));
        }

        Expense expense = createShared("10.00", items);

        assertThat(expense.items()).extracting(ExpenseItem::amountMinor).containsExactly(334L, 333L, 333L);
    }

    // ------------------------------------------------------------------ merchant, note, label

    @Test
    void BR_EXP_01_the_merchant_is_stored_as_given_with_a_normalised_key() {
        Expense expense = Expense.create(household, LEDGER, CLOCK, eur("5.00"), TODAY_IN_PARIS,
                List.of(item(groceries, "5.00")), creator, SharingType.SHARED, creator, "  CARREFOUR city ", null);

        assertThat(expense.merchantDisplay()).isEqualTo("  CARREFOUR city ");
    }

    @Test
    void BR_EXP_01_blank_optional_fields_are_absent_and_oversized_ones_rejected() {
        Expense blank = Expense.create(household, LEDGER, CLOCK, eur("5.00"), TODAY_IN_PARIS,
                List.of(item(groceries, "5.00")), creator, SharingType.SHARED, creator, "  ", "   ");
        assertThat(blank.merchantDisplay()).isNull();
        assertThat(blank.note()).isNull();

        assertRejected(() -> Expense.create(household, LEDGER, CLOCK, eur("5.00"), TODAY_IN_PARIS,
                List.of(item(groceries, "5.00")), creator, SharingType.SHARED, creator, null, "x".repeat(501)),
                "VALIDATION_FAILED");
        assertRejected(() -> Expense.create(household, LEDGER, CLOCK, eur("5.00"), TODAY_IN_PARIS,
                List.of(item(groceries, "5.00")), creator, SharingType.SHARED, creator, "m".repeat(121), null),
                "VALIDATION_FAILED");
        assertRejected(() -> Expense.create(household, LEDGER, CLOCK, eur("5.00"), TODAY_IN_PARIS,
                List.of(new NewItem(groceries, eur("5.00"), "l".repeat(61))), creator, SharingType.SHARED, creator,
                null, null), "VALIDATION_FAILED");
        assertRejected(() -> Expense.create(household, LEDGER, CLOCK, eur("5.00"), TODAY_IN_PARIS,
                List.of(item(groceries, "5.00")), creator, SharingType.SHARED, creator, "***", null),
                "EXPENSE_MERCHANT_INVALID");
    }
}
