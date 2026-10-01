package com.couplefinance.expense.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.couplefinance.categorization.api.CategoryCatalogue;
import com.couplefinance.expense.domain.Expense;
import com.couplefinance.expense.domain.ExpenseAuditLog;
import com.couplefinance.expense.domain.ExpenseRepository;
import com.couplefinance.expense.domain.SharingType;
import com.couplefinance.household.api.CurrentHousehold;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.household.api.HouseholdLedgerRules;
import com.couplefinance.household.api.LedgerProfile;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.shared.money.CurrencyCode;
import com.couplefinance.shared.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** Use-case rules of expense creation that need no database: BR-EXP-14, BR-EXP-07, BR-HH-10. */
class ExpenseCreatorTest {

    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC);

    private final HouseholdId household = new HouseholdId(UUID.randomUUID());
    private final UserId user = new UserId(UUID.randomUUID());
    private final UUID category = UUID.randomUUID();
    private final CurrentHousehold currentHousehold = mock(CurrentHousehold.class);
    private final HouseholdLedgerRules ledgerRules = mock(HouseholdLedgerRules.class);
    private final CategoryCatalogue catalogue = mock(CategoryCatalogue.class);
    private final ExpenseRepository repository = mock(ExpenseRepository.class);
    private final ExpenseAuditLog audit = mock(ExpenseAuditLog.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ExpenseCreator creator = new ExpenseCreator(currentHousehold, ledgerRules, catalogue, repository,
            audit, events, CLOCK);

    private void active() {
        when(currentHousehold.currentHousehold()).thenReturn(new HouseholdContext(household, user,
                HouseholdContext.Status.ACTIVE, HouseholdContext.Role.MEMBER));
        when(ledgerRules.profile(household)).thenReturn(
                new LedgerProfile(EUR, ZoneId.of("Europe/Paris"), 2, Money.ofMinor(100_000_000, EUR, 2)));
        when(ledgerRules.isActiveMember(household, user)).thenReturn(true);
    }

    private CreateExpenseCommand command() {
        Money five = Money.parse("5.00", EUR, 2);
        return new CreateExpenseCommand(five, LocalDate.of(2026, 10, 1),
                List.of(new CreateExpenseCommand.Item(category, five, null)), user.value(), SharingType.SHARED,
                null, null);
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApplicationException.class,
                e -> assertThat(e.errorCode().code()).isEqualTo(code));
    }

    @Test
    void BR_EXP_14_category_must_be_active() {
        active();
        when(catalogue.unknownCategories(household, Set.of(category))).thenReturn(Set.of());
        when(catalogue.unusableCategories(household, Set.of(category))).thenReturn(Set.of(category));

        assertCode(() -> creator.create(command()), "EXPENSE_CATEGORY_ARCHIVED");
        verify(repository, never()).saveAndFlush(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void BR_EXP_14_a_category_unknown_to_the_household_is_not_found() {
        active();
        when(catalogue.unknownCategories(household, Set.of(category))).thenReturn(Set.of(category));

        assertCode(() -> creator.create(command()), "CATEGORY_NOT_FOUND");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void BR_EXP_14_an_active_or_system_category_is_accepted_and_audit_and_event_follow() {
        active();
        when(catalogue.unknownCategories(household, Set.of(category))).thenReturn(Set.of());
        when(catalogue.unusableCategories(household, Set.of(category))).thenReturn(Set.of());
        when(repository.saveAndFlush(any(Expense.class))).thenAnswer(call -> call.getArgument(0));

        ExpenseView view = creator.create(command());

        assertThat(view.amount()).isEqualTo(Money.parse("5.00", EUR, 2));
        verify(audit).created(any(Expense.class), any(UserId.class));
        verify(events).publishEvent(any(Object.class));
    }

    @Test
    void BR_EXP_07_paid_by_must_be_an_active_member() {
        active();
        UserId stranger = new UserId(UUID.randomUUID());
        when(ledgerRules.isActiveMember(household, stranger)).thenReturn(false);
        Money five = Money.parse("5.00", EUR, 2);
        CreateExpenseCommand command = new CreateExpenseCommand(five, LocalDate.of(2026, 10, 1),
                List.of(new CreateExpenseCommand.Item(category, five, null)), stranger.value(), SharingType.SHARED,
                null, null);

        assertCode(() -> creator.create(command), "EXPENSE_PAID_BY_INVALID");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void BR_HH_10_an_archive_reader_cannot_create_an_expense() {
        when(currentHousehold.currentHousehold()).thenReturn(new HouseholdContext(household, user,
                HouseholdContext.Status.DISSOLVED, HouseholdContext.Role.ARCHIVE_READER));

        assertCode(() -> creator.create(command()), "HOUSEHOLD_READ_ONLY");
        verify(repository, never()).saveAndFlush(any());
        verify(events, never()).publishEvent(any(Object.class));
    }
}
