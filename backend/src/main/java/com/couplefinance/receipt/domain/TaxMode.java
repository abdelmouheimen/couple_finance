package com.couplefinance.receipt.domain;

/** BR-RCP-06: whether printed prices include tax. */
public enum TaxMode {
    /** Prices include tax; tax lines are informational. */
    INCLUSIVE,
    /** subtotal + tax = total. */
    EXCLUSIVE,
    /** No tax check. */
    UNKNOWN
}
