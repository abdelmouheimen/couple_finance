package com.couplefinance.budget.domain;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.couplefinance.budget.domain.BudgetCategoryLimits.Line;

/** Rules of copying the budget of the previous period (BR-BUD-02, BR-CAT-03). */
public final class BudgetCopy {

    private BudgetCopy() {
    }

    /**
     * BR-CAT-03: the lines of {@code source} that are copied - those on a category that is still usable; lines of
     * archived (or otherwise unusable) categories are omitted, never converted. Order is preserved.
     */
    public static List<Line> copiedLines(List<Line> source, Set<UUID> unusableCategories) {
        return source.stream().filter(line -> !unusableCategories.contains(line.categoryId())).toList();
    }
}
