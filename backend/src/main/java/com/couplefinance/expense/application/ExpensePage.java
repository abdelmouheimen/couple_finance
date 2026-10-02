package com.couplefinance.expense.application;

import java.util.List;

import com.couplefinance.expense.domain.ExpenseScope;
import com.couplefinance.shared.money.Money;
import org.jspecify.annotations.Nullable;

/**
 * One page of the history with the server-computed totals of the whole filtered set.
 *
 * @param totals labelled with their scope (BR-SCP-03)
 */
public record ExpensePage(List<ExpenseView> items, @Nullable String nextCursor, Totals totals) {

    /** @param net EXPENSE minus REFUND, TRANSFER excluded; negative when refunds exceed expenses */
    public record Totals(ExpenseScope scope, long count, Money net) {}
}
