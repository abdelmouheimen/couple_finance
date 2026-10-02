package com.couplefinance.budget.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.money.CurrencyCode;
import com.couplefinance.shared.money.Money;
import org.jspecify.annotations.Nullable;

/** Invariants of the category limit lines of one budget (BR-BUD-01, BR-MON-02/03/07). */
public final class CategoryLimits {

    /** Upper bound of lines in a budget; keeps the sum of limits (each at most 10^15) far from overflow. */
    public static final int MAX_LINES = 200;

    private CategoryLimits() {
    }

    /**
     * Validates a requested set of lines: at most one per category (BR-BUD-01), each limit strictly positive, in
     * the household currency and at most the storable maximum.
     *
     * @throws ApplicationException the first violated rule, with a stable error code
     */
    public static void validate(List<CategoryLimit> lines, CurrencyCode currency, int decimals) {
        if (lines.size() > MAX_LINES) {
            throw new ApplicationException(BudgetErrorCode.TOO_MANY_CATEGORY_LIMITS,
                    "A budget holds at most " + MAX_LINES + " category limits.");
        }
        Set<UUID> seen = new HashSet<>();
        Money maximum = Money.ofMinor(Budget.MAX_LIMIT_MINOR, currency, decimals);
        for (CategoryLimit line : lines) {
            if (!seen.add(line.categoryId())) {
                throw new ApplicationException(BudgetErrorCode.DUPLICATE_CATEGORY_LIMIT,
                        "A budget has at most one limit per category.");
            }
            line.limit().requirePositiveAtMost(maximum);
        }
    }

    /**
     * BR-BUD-04: the sum of the category limits, when it exceeds the overall limit; {@code null} otherwise,
     * including without an overall limit. A warning only: it never blocks and is never stored.
     */
    public static @Nullable CategoryLimitsWarning warningFor(@Nullable Money overallLimit, List<CategoryLimit> lines,
            CurrencyCode currency, int decimals) {
        if (overallLimit == null || lines.isEmpty()) {
            return null;
        }
        long total = 0;
        for (CategoryLimit line : lines) {
            total = Math.addExact(total, line.limit().minorUnits());
        }
        if (total <= overallLimit.minorUnits()) {
            return null;
        }
        return new CategoryLimitsWarning(Money.ofMinor(total, currency, decimals), overallLimit);
    }
}
