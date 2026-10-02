package com.couplefinance.expense.api;

/** BR-SCP-03: the scope every spending total is labelled with. */
public enum SpendingScope {
    /** SHARED expenses of the household (BR-SCP-01); never includes any PERSONAL expense. */
    HOUSEHOLD,
    /** The caller's own PERSONAL expenses (BR-SCP-02); never the partner's. */
    PERSONAL
}
