package com.couplefinance.receipt.domain;

import com.couplefinance.shared.money.Money;

/** Expense kind proposed from the receipt total (BR-RCP-04, BR-EXP-02). The user still confirms it. */
public enum ProposedKind {
    EXPENSE,
    REFUND;

    /** A negative total proposes {@link #REFUND}; otherwise {@link #EXPENSE}. */
    public static ProposedKind forTotal(Money total) {
        return total.isNegative() ? REFUND : EXPENSE;
    }
}
