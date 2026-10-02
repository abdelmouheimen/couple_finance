package com.couplefinance.budget.domain;

/** BR-BUD-06 (status part): how far a limit is consumed. */
public enum BudgetStatus {
    /** Consumed strictly below 80% of the limit. */
    ON_TRACK,
    /** Consumed from 80% to 100% of the limit, both inclusive. */
    WARNING,
    /** Consumed strictly above 100% of the limit. */
    EXCEEDED
}
