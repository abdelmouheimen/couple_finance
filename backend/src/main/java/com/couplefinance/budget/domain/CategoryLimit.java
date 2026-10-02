package com.couplefinance.budget.domain;

import java.util.Objects;
import java.util.UUID;

import com.couplefinance.shared.money.Money;

/** One line of a budget: the spending limit of one category for the period (domain-model.md section 8.1). */
public record CategoryLimit(UUID categoryId, Money limit) {

    public CategoryLimit {
        Objects.requireNonNull(categoryId, "categoryId");
        Objects.requireNonNull(limit, "limit");
    }
}
