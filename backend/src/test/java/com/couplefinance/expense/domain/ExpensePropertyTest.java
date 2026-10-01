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

/** Property-based tests of the item invariants and even splits (BR-EXP-08, BR-MON-06). */
class ExpensePropertyTest {

    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC);
    private static final LedgerProfile LEDGER =
            new LedgerProfile(EUR, ZoneId.of("Europe/Paris"), 2, Money.ofMinor(100_000_000, EUR, 2));
    private static final HouseholdId HOUSEHOLD = new HouseholdId(UUID.randomUUID());
    private static final UserId USER = new UserId(UUID.randomUUID());

    private static Expense create(Money amount, List<NewItem> items) {
        return Expense.create(HOUSEHOLD, LEDGER, CLOCK, amount, LocalDate.of(2026, 10, 1), items, USER,
                SharingType.SHARED, USER, null, null);
    }

    private static List<NewItem> itemsOf(List<Money> amounts) {
        List<NewItem> items = new ArrayList<>();
        for (Money amount : amounts) {
            items.add(new NewItem(UUID.randomUUID(), amount, null));
        }
        return items;
    }

    @Property
    void BR_EXP_08_items_summing_to_the_amount_are_always_accepted_and_preserve_the_total(
            @ForAll("amountsMinor") long totalMinor, @ForAll("parts") int parts) {
        Money total = Money.ofMinor(totalMinor, EUR, 2);
        List<Money> shares = total.split(Math.min(parts, (int) Math.min(totalMinor, 10)));

        Expense expense = create(total, itemsOf(shares));

        assertThat(expense.items().stream().mapToLong(ExpenseItem::amountMinor).sum()).isEqualTo(totalMinor);
        assertThat(expense.amountMinor()).isEqualTo(totalMinor);
    }

    @Property
    void BR_EXP_08_items_that_do_not_sum_to_the_amount_are_always_rejected(
            @ForAll("amountsMinor") long totalMinor, @ForAll("parts") int parts, @ForAll("drift") long drift) {
        Money total = Money.ofMinor(totalMinor, EUR, 2);
        int count = Math.min(parts, (int) Math.min(totalMinor, 10));
        List<Money> shares = new ArrayList<>(total.split(count));
        Money first = shares.get(0);
        Money changed = Money.ofMinor(first.minorUnits() + drift, EUR, 2);
        if (changed.minorUnits() <= 0) {
            return; // would be rejected for another reason (not positive)
        }
        shares.set(0, changed);

        assertThatThrownBy(() -> create(total, itemsOf(shares))).isInstanceOfSatisfying(ApplicationException.class,
                e -> assertThat(e.errorCode().code()).isIn("EXPENSE_ITEMS_SUM_MISMATCH", "AMOUNT_EXCEEDS_MAXIMUM"));
    }

    @Provide
    Arbitrary<Long> amountsMinor() {
        return Arbitraries.longs().between(1, 100_000_000L);
    }

    @Provide
    Arbitrary<Integer> parts() {
        return Arbitraries.integers().between(1, 10);
    }

    @Provide
    Arbitrary<Long> drift() {
        return Arbitraries.longs().between(-1_000, 1_000).filter(d -> d != 0);
    }
}
