package com.couplefinance.expense.application;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.couplefinance.expense.domain.Expense;
import com.couplefinance.expense.domain.ExpenseErrorCode;
import com.couplefinance.expense.domain.ExpenseRepository;
import com.couplefinance.expense.domain.ExpenseSearch;
import com.couplefinance.household.api.CurrentHousehold;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.household.api.HouseholdLedgerRules;
import com.couplefinance.household.api.LedgerProfile;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.money.Money;
import com.couplefinance.shared.pagination.CursorCodec;
import com.couplefinance.shared.pagination.CursorPage;
import com.couplefinance.shared.pagination.PaginationErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lists and searches the expense history (F9). Read-only: it also serves a former member reading the archive of a
 * dissolved household (BR-HH-10). The household and the user come from the authenticated principal; the view
 * (household or personal) decides which expenses can match at all (BR-EXP-07, BR-SCP-01/02), so neither results,
 * counts, totals nor errors can reveal a partner's PERSONAL expense.
 */
@Service
public class ListExpensesService {

    static final int TEXT_MAX_LENGTH = 100;

    private final CurrentHousehold currentHousehold;
    private final HouseholdLedgerRules ledgerRules;
    private final ExpenseSearch search;
    private final ExpenseRepository expenses;
    private final CursorCodec cursorCodec;

    ListExpensesService(CurrentHousehold currentHousehold, HouseholdLedgerRules ledgerRules, ExpenseSearch search,
            ExpenseRepository expenses, CursorCodec cursorCodec) {
        this.currentHousehold = currentHousehold;
        this.ledgerRules = ledgerRules;
        this.search = search;
        this.expenses = expenses;
        this.cursorCodec = cursorCodec;
    }

    @Transactional(readOnly = true)
    public ExpensePage list(ListExpensesQuery query) {
        HouseholdContext context = currentHousehold.currentHousehold();
        if (query.dateFrom() != null && query.dateTo() != null && query.dateFrom().isAfter(query.dateTo())) {
            throw new ApplicationException(ExpenseErrorCode.EXPENSE_FILTER_INVALID,
                    "dateFrom must not be after dateTo.");
        }
        String text = query.text() == null ? null : query.text().strip();
        if (text != null && text.length() > TEXT_MAX_LENGTH) {
            throw new ApplicationException(ExpenseErrorCode.EXPENSE_FILTER_INVALID,
                    "The search text must have at most " + TEXT_MAX_LENGTH + " characters.");
        }
        if (text != null && text.isEmpty()) {
            text = null;
        }
        UUID household = context.householdId().value();
        UUID user = context.userId().value();
        LedgerProfile ledger = ledgerRules.profile(context.householdId());
        ExpenseSearch.Criteria criteria = new ExpenseSearch.Criteria(household, query.scope().sharingType(), user,
                query.dateFrom(), query.dateTo(), query.categoryId(), query.paidByUserId(), query.kind(),
                query.hasReceipt(), text);

        String cursorScope = user + "|" + household + "|expenses|" + query.scope();
        Optional<ExpenseSearch.Position> after = query.page().cursor().map(cursor -> position(cursorScope, cursor));
        List<ExpenseSearch.Position> fetched = search.page(criteria, after, query.page().fetchSize());
        CursorPage<ExpenseSearch.Position> page = CursorPage.of(fetched, query.page(),
                position -> List.of(position.date().toString(), position.id().toString()), cursorCodec, cursorScope);

        Map<UUID, Expense> loaded = new HashMap<>();
        if (!page.items().isEmpty()) {
            expenses.findVisibleLiveByIds(page.items().stream().map(ExpenseSearch.Position::id).toList(),
                    household, user).forEach(expense -> loaded.put(expense.id(), expense));
        }
        List<ExpenseView> items = page.items().stream().map(position -> loaded.get(position.id()))
                .filter(Objects::nonNull)
                .map(expense -> ExpenseView.of(expense, ledger.decimals())).toList();

        ExpenseSearch.Totals totals = search.totals(criteria);
        return new ExpensePage(items, page.nextCursor(), new ExpensePage.Totals(query.scope(), totals.count(),
                Money.ofMinor(totals.netMinor(), ledger.currency(), ledger.decimals())));
    }

    private ExpenseSearch.Position position(String scope, String cursor) {
        List<String> values = cursorCodec.decode(scope, cursor);
        try {
            if (values.size() != 2) {
                throw new IllegalArgumentException();
            }
            return new ExpenseSearch.Position(LocalDate.parse(values.get(0)), UUID.fromString(values.get(1)));
        } catch (RuntimeException e) {
            throw new ApplicationException(PaginationErrorCode.INVALID_CURSOR,
                    "The cursor is invalid.");
        }
    }
}
