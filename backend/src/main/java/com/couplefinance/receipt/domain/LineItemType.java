package com.couplefinance.receipt.domain;

import com.couplefinance.shared.money.Money;

/** BR-RCP-04: signed, typed receipt line items. */
public enum LineItemType {
    ITEM(false),
    DISCOUNT(true),
    DEPOSIT(false),
    DEPOSIT_RETURN(true),
    TIP(false),
    FEE(false),
    OTHER(false);

    private final boolean nonPositive;

    LineItemType(boolean nonPositive) {
        this.nonPositive = nonPositive;
    }

    /** {@code DISCOUNT} and {@code DEPOSIT_RETURN} must be &lt;= 0, every other type &gt;= 0 (ai.md §4.3). */
    public boolean acceptsSign(Money amount) {
        return nonPositive ? !amount.isPositive() : !amount.isNegative();
    }
}
