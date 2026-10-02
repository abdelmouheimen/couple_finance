package com.couplefinance.expense.application;

import java.time.LocalDate;
import java.util.UUID;

import com.couplefinance.expense.domain.ExpenseKind;
import com.couplefinance.expense.domain.ExpenseScope;
import com.couplefinance.shared.pagination.PageQuery;
import org.jspecify.annotations.Nullable;

/** Filters of the expense history (features.md F9); every filter is optional except the scope. */
public record ListExpensesQuery(ExpenseScope scope, @Nullable LocalDate dateFrom, @Nullable LocalDate dateTo,
        @Nullable UUID categoryId, @Nullable UUID paidByUserId, @Nullable ExpenseKind kind,
        @Nullable Boolean hasReceipt, @Nullable String text, PageQuery page) {}
