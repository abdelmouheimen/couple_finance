package com.couplefinance.expense.domain;

import java.util.UUID;

import org.springframework.data.repository.Repository;

/**
 * Persistence of expenses. Deliberately has no generic finder: every read of household data added later must be
 * scoped by household and by visibility (BR-EXP-07), never {@code findById}.
 */
public interface ExpenseRepository extends Repository<Expense, UUID> {

    Expense saveAndFlush(Expense expense);
}
