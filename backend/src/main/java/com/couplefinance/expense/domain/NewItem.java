package com.couplefinance.expense.domain;

import java.util.Objects;
import java.util.UUID;

import com.couplefinance.shared.money.Money;
import org.jspecify.annotations.Nullable;

/** An item of an expense about to be created: category, amount and optional label (BR-EXP-08). */
public record NewItem(UUID categoryId, Money amount, @Nullable String label) {

    public NewItem {
        Objects.requireNonNull(categoryId, "categoryId");
        Objects.requireNonNull(amount, "amount");
    }
}
