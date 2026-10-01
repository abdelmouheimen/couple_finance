package com.couplefinance.categorization.domain;

import com.couplefinance.shared.id.HouseholdId;

/** Concurrency-safe allocation of the {@code sort_order} of a new custom category. */
public interface SortOrderAllocator {

    /**
     * The sort order after the household's current highest one ({@code max + 1}, 1 for its first custom
     * category). Serialises concurrent allocations for the same household until the calling transaction ends, so
     * two concurrent creations can never receive the same value; {@code uq_category_sort_order} is the database
     * backstop. Must be called inside the transaction that inserts the category.
     */
    int next(HouseholdId household);
}
