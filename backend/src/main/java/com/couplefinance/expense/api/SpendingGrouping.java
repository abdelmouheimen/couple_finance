package com.couplefinance.expense.api;

/** Optional breakdowns of a {@link SpendingReport}. */
public enum SpendingGrouping {
    /** By category of the expense items (BR-EXP-08). */
    CATEGORY,
    /** By payer, never by creator (BR-ANA-04). */
    PAYER,
    /** By expense date (a refund counts on its own date). */
    DAY
}
