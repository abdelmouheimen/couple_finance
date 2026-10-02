package com.couplefinance.expense.api;

import java.time.LocalDate;
import java.util.Set;

import com.couplefinance.household.api.HouseholdContext;

/**
 * The single source of spending figures (domain-model.md section 7, BR-SCP-01..03) for budgets, analytics and
 * later consumers. Computed in SQL with integer minor-unit arithmetic. Read-only: it also serves a former member
 * reading a dissolved household's archive (BR-HH-10).
 *
 * <p>The household and the user come from the caller's {@link HouseholdContext}; there is deliberately no way to
 * name another user, so a partner's PERSONAL spending cannot be read, and the {@link SpendingScope#HOUSEHOLD}
 * figures never include any PERSONAL expense.
 */
public interface SpendingQuery {

    /**
     * @param context   the caller's household context
     * @param scope     {@code HOUSEHOLD} or the caller's own {@code PERSONAL} spending
     * @param start     inclusive start date
     * @param end       exclusive end date; must be after {@code start}
     * @param groupings requested breakdowns, possibly empty
     * @throws com.couplefinance.shared.error.ApplicationException {@code EXPENSE_FILTER_INVALID} when
     *         {@code end} is not after {@code start}
     */
    SpendingReport spending(HouseholdContext context, SpendingScope scope, LocalDate start, LocalDate end,
            Set<SpendingGrouping> groupings);
}
