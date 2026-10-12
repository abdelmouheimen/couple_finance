package com.couplefinance.expense.domain;

/** How an expense came to exist: entered by hand, or confirmed by a member from a receipt draft (BR-RCP-02). */
public enum ExpenseSource {
    MANUAL,
    RECEIPT
}
